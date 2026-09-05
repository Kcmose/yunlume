package com.example.nav.module.datapackage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.bookmark.entity.Bookmark;
import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.category.entity.Category;
import com.example.nav.module.category.mapper.CategoryMapper;
import com.example.nav.module.customlink.entity.CustomLink;
import com.example.nav.module.customlink.mapper.CustomLinkMapper;
import com.example.nav.module.datapackage.model.PortablePackageModels.AssetDescriptor;
import com.example.nav.module.datapackage.model.PortablePackageModels.BookmarkData;
import com.example.nav.module.datapackage.model.PortablePackageModels.CategoryData;
import com.example.nav.module.datapackage.model.PortablePackageModels.CustomLinkData;
import com.example.nav.module.datapackage.model.PortablePackageModels.ParsedPackage;
import com.example.nav.module.datapackage.model.PortablePackageModels.SearchEngineData;
import com.example.nav.module.datapackage.model.PortablePackageModels.SiteConfigData;
import com.example.nav.module.search.entity.SearchEngine;
import com.example.nav.module.search.mapper.SearchEngineMapper;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.publicdata.PublicDataCacheNames;
import com.example.nav.module.publicdata.PublicDataCacheInvalidator;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.upload.service.BackgroundImageStorageService;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PortableImportTransactionService {

    private final SiteConfigMapper siteConfigMapper;
    private final CategoryMapper categoryMapper;
    private final BookmarkMapper bookmarkMapper;
    private final SearchEngineMapper searchEngineMapper;
    private final CustomLinkMapper customLinkMapper;
    private final BackgroundImageStorageService imageStorageService;
    private final PortableDataSnapshotService snapshotService;
    private final PublicDataCacheInvalidator cacheInvalidator;
    private final PortableImportCommitStore commitStore;

    public PortableImportTransactionService(
            SiteConfigMapper siteConfigMapper,
            CategoryMapper categoryMapper,
            BookmarkMapper bookmarkMapper,
            SearchEngineMapper searchEngineMapper,
            CustomLinkMapper customLinkMapper,
            BackgroundImageStorageService imageStorageService,
            PortableDataSnapshotService snapshotService,
            PublicDataCacheInvalidator cacheInvalidator,
            PortableImportCommitStore commitStore
    ) {
        this.siteConfigMapper = siteConfigMapper;
        this.categoryMapper = categoryMapper;
        this.bookmarkMapper = bookmarkMapper;
        this.searchEngineMapper = searchEngineMapper;
        this.customLinkMapper = customLinkMapper;
        this.imageStorageService = imageStorageService;
        this.snapshotService = snapshotService;
        this.cacheInvalidator = cacheInvalidator;
        this.commitStore = commitStore;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TransactionResult replaceBusinessData(
            ParsedPackage parsed,
            Path extractionRoot,
            String expectedRevision,
            String jobId,
            String previewToken,
            long userId,
            Instant createdAt,
            Instant startedAt,
            Runnable beforeWriting,
            Runnable beforeVerifying
    ) {
        if (parsed == null || !parsed.valid()) {
            throw BusinessException.badRequest("只有通过预检的数据包才能导入");
        }
        List<BackgroundImageStorageService.ImportedAsset> importedAssets = List.of();
        try {
            // Redis only gates scheduling. This row lock is the authoritative
            // writer serialization point and is held until database completion.
            commitStore.lockWriter();
            SiteConfig site = requireSingleSiteForUpdate();
            if (site.getVersion() == null || site.getVersion() < 0) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "站点配置缓存版本超出 0..2147483647 范围");
            }
            int oldVersion = site.getVersion();
            if (oldVersion == Integer.MAX_VALUE) {
                throw BusinessException.conflict("站点配置版本已达到上限，无法安全导入");
            }
            PortableDataSnapshotService.Snapshot transactionSnapshot = snapshotService.capture();
            if (expectedRevision == null || !expectedRevision.equals(transactionSnapshot.revision())) {
                throw BusinessException.conflict("业务数据在预检后已变化，请重新预检");
            }
            if (beforeWriting != null) beforeWriting.run();
            importedAssets = importReferencedAssets(parsed, extractionRoot);
            registerRollbackAssetCleanup(importedAssets);
            Map<String, String> assetUrls = new HashMap<>();
            importedAssets.forEach(asset -> assetUrls.put(asset.key(), asset.url()));

            applySite(site, parsed.data().siteConfig(), assetUrls, oldVersion + 1);
            if (updateSiteForImport(site, oldVersion) != 1) {
                throw BusinessException.conflict("站点配置在导入时发生变化，请重新预检");
            }

            // Delete children first so the replacement remains explicit and
            // does not depend on PostgreSQL cascade behavior.
            bookmarkMapper.delete(Wrappers.<Bookmark>lambdaQuery());
            categoryMapper.delete(Wrappers.<Category>lambdaQuery());
            searchEngineMapper.delete(Wrappers.<SearchEngine>lambdaQuery());
            customLinkMapper.delete(Wrappers.<CustomLink>lambdaQuery());

            LocalDateTime now = LocalDateTime.now();
            Map<String, Long> categoryIds = insertCategories(parsed.data().categories(), now);
            Map<String, Long> bookmarkIds = insertBookmarks(parsed.data().bookmarks(), categoryIds, now);
            Map<String, Long> searchEngineIds = insertSearchEngines(parsed.data().searchEngines(), now);
            Map<String, Long> customLinkIds = insertCustomLinks(parsed.data().customLinks(), now);
            if (beforeVerifying != null) beforeVerifying.run();
            verifyPersistedState(
                    parsed.data(), assetUrls, oldVersion + 1,
                    categoryIds, bookmarkIds, searchEngineIds, customLinkIds);
            cacheInvalidator.invalidateRecorded(oldVersion + 1L,
                    PublicDataCacheNames.SITE_CONFIG,
                    PublicDataCacheNames.NAVIGATION,
                    PublicDataCacheNames.SEARCH_ENGINES,
                    PublicDataCacheNames.CUSTOM_LINKS);
            // This marker commits atomically with every imported business row.
            commitStore.recordCommitted(
                    jobId, previewToken, userId, createdAt, startedAt, oldVersion + 1);
            return new TransactionResult(List.copyOf(importedAssets), oldVersion + 1);
        } catch (RuntimeException exception) {
            imageStorageService.deleteImportedAssets(importedAssets);
            throw exception;
        }
    }

    private void registerRollbackAssetCleanup(
            List<BackgroundImageStorageService.ImportedAsset> importedAssets
    ) {
        if (importedAssets.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("导入事务同步未启用");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    imageStorageService.deleteImportedAssets(importedAssets);
                }
            }
        });
    }

    private void verifyPersistedState(
            com.example.nav.module.datapackage.model.PortablePackageModels.PortableData expected,
            Map<String, String> assetUrls,
            int expectedSiteVersion,
            Map<String, Long> categoryIds,
            Map<String, Long> bookmarkIds,
            Map<String, Long> searchEngineIds,
            Map<String, Long> customLinkIds
    ) {
        List<SiteConfig> configs = siteConfigMapper.selectList(null);
        if (configs == null || configs.size() != 1
                || !Integer.valueOf(expectedSiteVersion).equals(configs.get(0).getVersion())) {
            throw new IllegalStateException("导入后站点配置版本校验失败");
        }
        verifySite(configs.get(0), expected.siteConfig(), assetUrls);
        long categories = categoryMapper.selectCount(null);
        long bookmarks = bookmarkMapper.selectCount(null);
        long searchEngines = searchEngineMapper.selectCount(null);
        long customLinks = customLinkMapper.selectCount(null);
        if (categories != expected.categories().size()
                || bookmarks != expected.bookmarks().size()
                || searchEngines != expected.searchEngines().size()
                || customLinks != expected.customLinks().size()) {
            throw new IllegalStateException("导入后业务资源数量校验失败");
        }
        long visibleDefaults = searchEngineMapper.selectCount(Wrappers.<SearchEngine>lambdaQuery()
                .eq(SearchEngine::getDefaultEngine, true)
                .eq(SearchEngine::getVisible, true));
        if (visibleDefaults != 1) {
            throw new IllegalStateException("导入后默认搜索引擎校验失败");
        }
        for (CategoryData category : expected.categories()) {
            Category actual = categoryMapper.selectById(categoryIds.get(category.key()));
            if (actual == null
                    || !category.name().trim().equals(actual.getName())
                    || !java.util.Objects.equals(nullable(category.icon()), actual.getIcon())
                    || !java.util.Objects.equals(category.sortOrder(), actual.getSortOrder())
                    || !java.util.Objects.equals(category.visible(), actual.getVisible())) {
                throw new IllegalStateException("导入后分类内容校验失败");
            }
        }
        for (BookmarkData bookmark : expected.bookmarks()) {
            Bookmark actual = bookmarkMapper.selectById(bookmarkIds.get(bookmark.key()));
            if (actual == null
                    || !java.util.Objects.equals(categoryIds.get(bookmark.categoryKey()), actual.getCategoryId())
                    || !bookmark.name().trim().equals(actual.getName())
                    || !bookmark.url().trim().equals(actual.getUrl())
                    || !java.util.Objects.equals(nullable(bookmark.icon()), actual.getIcon())
                    || !java.util.Objects.equals(nullable(bookmark.description()), actual.getDescription())
                    || !java.util.Objects.equals(bookmark.sortOrder(), actual.getSortOrder())
                    || !java.util.Objects.equals(bookmark.recommend(), actual.getRecommend())
                    || !java.util.Objects.equals(bookmark.external(), actual.getExternal())
                    || !java.util.Objects.equals(bookmark.visible(), actual.getVisible())) {
                throw new IllegalStateException("导入后书签内容校验失败");
            }
        }
        for (SearchEngineData engine : expected.searchEngines()) {
            SearchEngine actual = searchEngineMapper.selectById(searchEngineIds.get(engine.key()));
            if (actual == null
                    || !engine.name().trim().equals(actual.getName())
                    || !java.util.Objects.equals(nullable(engine.icon()), actual.getIcon())
                    || !engine.searchUrl().trim().equals(actual.getSearchUrl())
                    || !java.util.Objects.equals(nullable(engine.placeholder()), actual.getPlaceholder())
                    || !java.util.Objects.equals(engine.defaultEngine(), actual.getDefaultEngine())
                    || !java.util.Objects.equals(engine.sortOrder(), actual.getSortOrder())
                    || !java.util.Objects.equals(engine.visible(), actual.getVisible())) {
                throw new IllegalStateException("导入后搜索引擎内容校验失败");
            }
        }
        for (CustomLinkData link : expected.customLinks()) {
            CustomLink actual = customLinkMapper.selectById(customLinkIds.get(link.key()));
            if (actual == null
                    || !link.title().trim().equals(actual.getTitle())
                    || !link.url().trim().equals(actual.getUrl())
                    || !link.position().equals(actual.getPosition())
                    || !java.util.Objects.equals(link.sortOrder(), actual.getSortOrder())
                    || !java.util.Objects.equals(link.visible(), actual.getVisible())) {
                throw new IllegalStateException("导入后自定义链接内容校验失败");
            }
        }
    }

    private void verifySite(SiteConfig actual, SiteConfigData expected, Map<String, String> assetUrls) {
        if (!expected.siteName().trim().equals(actual.getSiteName())
                || !java.util.Objects.equals(nullable(expected.siteDescription()), actual.getSiteDescription())
                || !java.util.Objects.equals(nullable(expected.publishUrl()), actual.getPublishUrl())
                || !java.util.Objects.equals(expected.backgroundType(), actual.getBackgroundType())
                || !java.util.Objects.equals(expected.backgroundColor(), actual.getBackgroundColor())
                || !java.util.Objects.equals(
                        resolveBackground(expected.backgroundImage(), expected.backgroundImageAssetKey(), assetUrls),
                        actual.getBackgroundImage())
                || !java.util.Objects.equals(
                        resolveBackground(expected.mobileBackgroundImage(), expected.mobileBackgroundImageAssetKey(), assetUrls),
                        actual.getMobileBackgroundImage())
                || !java.util.Objects.equals(expected.fontColor(), actual.getFontColor())
                || !java.util.Objects.equals(expected.backgroundEffect(), actual.getBackgroundEffect())
                || !java.util.Objects.equals(expected.musicEnabled(), actual.getMusicEnabled())
                || !java.util.Objects.equals(nullable(expected.musicUrl()), actual.getMusicUrl())
                || !java.util.Objects.equals(expected.subscribeEnabled(), actual.getSubscribeEnabled())
                || !java.util.Objects.equals(expected.topContentEnabled(), actual.getTopContentEnabled())
                || !java.util.Objects.equals(nullable(expected.messageText()), actual.getMessageText())) {
            throw new IllegalStateException("导入后站点配置内容校验失败");
        }
    }

    private List<BackgroundImageStorageService.ImportedAsset> importReferencedAssets(
            ParsedPackage parsed,
            Path extractionRoot
    ) {
        SiteConfigData site = parsed.data().siteConfig();
        Set<String> keys = new LinkedHashSet<>();
        if (site.backgroundImageAssetKey() != null && !site.backgroundImageAssetKey().isBlank()) {
            keys.add(site.backgroundImageAssetKey());
        }
        if (site.mobileBackgroundImageAssetKey() != null && !site.mobileBackgroundImageAssetKey().isBlank()) {
            keys.add(site.mobileBackgroundImageAssetKey());
        }
        List<BackgroundImageStorageService.ImportAssetSource> sources = new ArrayList<>();
        for (String key : keys) {
            AssetDescriptor descriptor = parsed.assetsByKey().get(key);
            if (descriptor == null) throw BusinessException.badRequest("背景图片资产引用已失效");
            Path path = snapshotService.extractedAssetPath(extractionRoot, descriptor);
            String extension = descriptor.path().endsWith(".png") ? "png" : "jpg";
            sources.add(new BackgroundImageStorageService.ImportAssetSource(key, path, extension));
        }
        return imageStorageService.importValidatedAssets(sources);
    }

    private SiteConfig requireSingleSiteForUpdate() {
        List<SiteConfig> configs = siteConfigMapper.selectList(Wrappers.<SiteConfig>lambdaQuery()
                .orderByAsc(SiteConfig::getId)
                .last("FOR UPDATE"));
        if (configs == null || configs.size() != 1 || configs.get(0) == null
                || !Long.valueOf(1L).equals(configs.get(0).getId())) {
            throw BusinessException.conflict("站点配置必须且只能有一条，无法执行安全导入");
        }
        return configs.get(0);
    }

    private void applySite(
            SiteConfig target,
            SiteConfigData source,
            Map<String, String> assetUrls,
            int newVersion
    ) {
        target.setSiteName(source.siteName().trim());
        target.setSiteDescription(nullable(source.siteDescription()));
        target.setPublishUrl(nullable(source.publishUrl()));
        target.setBackgroundType(source.backgroundType());
        target.setBackgroundColor(source.backgroundColor());
        target.setBackgroundImage(resolveBackground(
                source.backgroundImage(), source.backgroundImageAssetKey(), assetUrls));
        target.setMobileBackgroundImage(resolveBackground(
                source.mobileBackgroundImage(), source.mobileBackgroundImageAssetKey(), assetUrls));
        target.setFontColor(source.fontColor());
        target.setBackgroundEffect(source.backgroundEffect());
        target.setMusicEnabled(source.musicEnabled());
        target.setMusicUrl(nullable(source.musicUrl()));
        target.setSubscribeEnabled(source.subscribeEnabled());
        target.setTopContentEnabled(source.topContentEnabled());
        target.setMessageText(nullable(source.messageText()));
        target.setVersion(newVersion);
        target.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * A portable import is a full replacement, so nullable values must be
     * written explicitly. BaseMapper.updateById uses the default NOT_NULL
     * strategy and would silently keep an old value when the package clears a
     * field. The row is already locked, while the version predicate provides a
     * final optimistic guard against an unexpected concurrent change.
     */
    private int updateSiteForImport(SiteConfig site, int expectedVersion) {
        return siteConfigMapper.update(null, Wrappers.<SiteConfig>lambdaUpdate()
                .eq(SiteConfig::getId, site.getId())
                .eq(SiteConfig::getVersion, expectedVersion)
                .set(SiteConfig::getSiteName, site.getSiteName())
                .set(SiteConfig::getSiteDescription, site.getSiteDescription())
                .set(SiteConfig::getPublishUrl, site.getPublishUrl())
                .set(SiteConfig::getBackgroundType, site.getBackgroundType())
                .set(SiteConfig::getBackgroundColor, site.getBackgroundColor())
                .set(SiteConfig::getBackgroundImage, site.getBackgroundImage())
                .set(SiteConfig::getMobileBackgroundImage, site.getMobileBackgroundImage())
                .set(SiteConfig::getFontColor, site.getFontColor())
                .set(SiteConfig::getBackgroundEffect, site.getBackgroundEffect())
                .set(SiteConfig::getMusicEnabled, site.getMusicEnabled())
                .set(SiteConfig::getMusicUrl, site.getMusicUrl())
                .set(SiteConfig::getSubscribeEnabled, site.getSubscribeEnabled())
                .set(SiteConfig::getTopContentEnabled, site.getTopContentEnabled())
                .set(SiteConfig::getMessageText, site.getMessageText())
                .set(SiteConfig::getVersion, site.getVersion())
                .set(SiteConfig::getUpdatedAt, site.getUpdatedAt()));
    }

    private String resolveBackground(String fallbackUrl, String assetKey, Map<String, String> assetUrls) {
        if (assetKey == null || assetKey.isBlank()) return nullable(fallbackUrl);
        String value = assetUrls.get(assetKey);
        if (value == null) throw BusinessException.badRequest("背景图片资产没有成功复制");
        return value;
    }

    private Map<String, Long> insertCategories(List<CategoryData> values, LocalDateTime now) {
        Map<String, Long> ids = new HashMap<>();
        for (CategoryData value : values) {
            Category category = new Category();
            category.setName(value.name().trim());
            category.setIcon(nullable(value.icon()));
            category.setSortOrder(value.sortOrder());
            category.setVisible(value.visible());
            category.setCreatedAt(now);
            category.setUpdatedAt(now);
            categoryMapper.insert(category);
            ids.put(value.key(), category.getId());
        }
        return ids;
    }

    private Map<String, Long> insertBookmarks(
            List<BookmarkData> values,
            Map<String, Long> categoryIds,
            LocalDateTime now
    ) {
        Map<String, Long> ids = new HashMap<>();
        for (BookmarkData value : values) {
            Long categoryId = categoryIds.get(value.categoryKey());
            if (categoryId == null) throw BusinessException.badRequest("书签分类映射失败");
            Bookmark bookmark = new Bookmark();
            bookmark.setCategoryId(categoryId);
            bookmark.setName(value.name().trim());
            bookmark.setUrl(value.url().trim());
            bookmark.setIcon(nullable(value.icon()));
            bookmark.setDescription(nullable(value.description()));
            bookmark.setSortOrder(value.sortOrder());
            bookmark.setRecommend(value.recommend());
            bookmark.setExternal(value.external());
            bookmark.setVisible(value.visible());
            bookmark.setCreatedAt(now);
            bookmark.setUpdatedAt(now);
            bookmarkMapper.insert(bookmark);
            ids.put(value.key(), bookmark.getId());
        }
        return ids;
    }

    private Map<String, Long> insertSearchEngines(List<SearchEngineData> values, LocalDateTime now) {
        // The database unique constraint permits only one active default. Write
        // non-default rows first, then the already validated single default.
        List<SearchEngineData> ordered = new ArrayList<>(values);
        ordered.sort(java.util.Comparator.comparing(data -> Boolean.TRUE.equals(data.defaultEngine())));
        Map<String, Long> ids = new HashMap<>();
        for (SearchEngineData value : ordered) {
            SearchEngine engine = new SearchEngine();
            engine.setName(value.name().trim());
            engine.setIcon(nullable(value.icon()));
            engine.setSearchUrl(value.searchUrl().trim());
            engine.setPlaceholder(nullable(value.placeholder()));
            engine.setDefaultEngine(value.defaultEngine());
            engine.setSortOrder(value.sortOrder());
            engine.setVisible(value.visible());
            engine.setCreatedAt(now);
            engine.setUpdatedAt(now);
            searchEngineMapper.insert(engine);
            ids.put(value.key(), engine.getId());
        }
        return ids;
    }

    private Map<String, Long> insertCustomLinks(List<CustomLinkData> values, LocalDateTime now) {
        Map<String, Long> ids = new HashMap<>();
        for (CustomLinkData value : values) {
            CustomLink link = new CustomLink();
            link.setTitle(value.title().trim());
            link.setUrl(value.url().trim());
            link.setPosition(value.position());
            link.setSortOrder(value.sortOrder());
            link.setVisible(value.visible());
            link.setCreatedAt(now);
            link.setUpdatedAt(now);
            customLinkMapper.insert(link);
            ids.put(value.key(), link.getId());
        }
        return ids;
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record TransactionResult(
            List<BackgroundImageStorageService.ImportedAsset> importedAssets,
            int siteVersion
    ) {
    }
}
