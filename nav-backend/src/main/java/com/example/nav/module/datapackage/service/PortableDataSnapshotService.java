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
import com.example.nav.module.datapackage.model.PortablePackageModels.PortableData;
import com.example.nav.module.datapackage.model.PortablePackageModels.SearchEngineData;
import com.example.nav.module.datapackage.model.PortablePackageModels.SiteConfigData;
import com.example.nav.module.search.entity.SearchEngine;
import com.example.nav.module.search.mapper.SearchEngineMapper;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.upload.config.UploadStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class PortableDataSnapshotService {

    private static final Pattern MANAGED_FILENAME = Pattern.compile("^[a-f0-9]{32}\\.(?:jpg|png)$");

    private final SiteConfigMapper siteConfigMapper;
    private final CategoryMapper categoryMapper;
    private final BookmarkMapper bookmarkMapper;
    private final SearchEngineMapper searchEngineMapper;
    private final CustomLinkMapper customLinkMapper;
    private final ObjectMapper objectMapper;
    private final Path uploadRoot;
    private final String managedUrlPrefix;

    public PortableDataSnapshotService(
            SiteConfigMapper siteConfigMapper,
            CategoryMapper categoryMapper,
            BookmarkMapper bookmarkMapper,
            SearchEngineMapper searchEngineMapper,
            CustomLinkMapper customLinkMapper,
            ObjectMapper objectMapper,
            UploadStorageProperties uploadProperties
    ) {
        this.siteConfigMapper = siteConfigMapper;
        this.categoryMapper = categoryMapper;
        this.bookmarkMapper = bookmarkMapper;
        this.searchEngineMapper = searchEngineMapper;
        this.customLinkMapper = customLinkMapper;
        this.objectMapper = objectMapper;
        this.uploadRoot = Path.of(uploadProperties.getDirectory()).toAbsolutePath().normalize();
        String base = uploadProperties.getBaseUrl() == null || uploadProperties.getBaseUrl().isBlank()
                ? "/uploads"
                : uploadProperties.getBaseUrl().trim();
        while (base.length() > 1 && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.managedUrlPrefix = ("/".equals(base) ? "" : base) + "/backgrounds/";
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot capture() {
        List<SiteConfig> configs = siteConfigMapper.selectList(Wrappers.<SiteConfig>lambdaQuery()
                .orderByAsc(SiteConfig::getId));
        if (configs == null || configs.size() != 1 || configs.get(0) == null) {
            throw unavailable("站点配置必须且只能有一条，无法生成可靠数据快照");
        }
        SiteConfig site = configs.get(0);

        Map<String, SnapshotAsset> assets = new LinkedHashMap<>();
        AssetReference desktop = resolveAsset(site.getBackgroundImage(), assets);
        AssetReference mobile = resolveAsset(site.getMobileBackgroundImage(), assets);

        SiteConfigData siteData = new SiteConfigData(
                "site-config",
                site.getSiteName(),
                emptyIfNull(site.getSiteDescription()),
                emptyIfNull(site.getPublishUrl()),
                site.getBackgroundType(),
                site.getBackgroundColor(),
                emptyIfNull(site.getBackgroundImage()),
                desktop.assetKey(),
                emptyIfNull(site.getMobileBackgroundImage()),
                mobile.assetKey(),
                site.getFontColor(),
                Boolean.TRUE.equals(site.getBackgroundEffect()),
                Boolean.TRUE.equals(site.getMusicEnabled()),
                emptyIfNull(site.getMusicUrl()),
                Boolean.TRUE.equals(site.getSubscribeEnabled()),
                Boolean.TRUE.equals(site.getTopContentEnabled()),
                emptyIfNull(site.getMessageText())
        );

        List<CategoryData> categories = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .orderByAsc(Category::getSortOrder, Category::getId))
                .stream()
                .map(category -> new CategoryData(
                        categoryKey(category.getId()),
                        category.getName(),
                        emptyIfNull(category.getIcon()),
                        category.getSortOrder(),
                        Boolean.TRUE.equals(category.getVisible())
                ))
                .toList();

        List<BookmarkData> bookmarks = bookmarkMapper.selectList(Wrappers.<Bookmark>lambdaQuery()
                        .orderByAsc(Bookmark::getCategoryId, Bookmark::getSortOrder, Bookmark::getId))
                .stream()
                .map(bookmark -> new BookmarkData(
                        bookmarkKey(bookmark.getId()),
                        categoryKey(bookmark.getCategoryId()),
                        bookmark.getName(),
                        bookmark.getUrl(),
                        emptyIfNull(bookmark.getIcon()),
                        emptyIfNull(bookmark.getDescription()),
                        bookmark.getSortOrder(),
                        Boolean.TRUE.equals(bookmark.getRecommend()),
                        Boolean.TRUE.equals(bookmark.getExternal()),
                        Boolean.TRUE.equals(bookmark.getVisible())
                ))
                .toList();

        List<SearchEngineData> searchEngines = searchEngineMapper.selectList(
                        Wrappers.<SearchEngine>lambdaQuery().orderByAsc(SearchEngine::getSortOrder, SearchEngine::getId))
                .stream()
                .map(engine -> new SearchEngineData(
                        searchEngineKey(engine.getId()),
                        engine.getName(),
                        emptyIfNull(engine.getIcon()),
                        engine.getSearchUrl(),
                        emptyIfNull(engine.getPlaceholder()),
                        Boolean.TRUE.equals(engine.getDefaultEngine()),
                        engine.getSortOrder(),
                        Boolean.TRUE.equals(engine.getVisible())
                ))
                .toList();

        List<CustomLinkData> customLinks = customLinkMapper.selectList(null).stream()
                .sorted(Comparator.comparing(CustomLink::getPosition, Comparator.nullsLast(String::compareTo))
                        .thenComparing(CustomLink::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(CustomLink::getId, Comparator.nullsLast(Long::compareTo)))
                .map(link -> new CustomLinkData(
                        customLinkKey(link.getId()),
                        link.getTitle(),
                        link.getUrl(),
                        link.getPosition(),
                        link.getSortOrder(),
                        Boolean.TRUE.equals(link.getVisible())
                ))
                .toList();

        PortableData data = new PortableData(siteData, categories, bookmarks, searchEngines, customLinks);
        int version = site.getVersion() == null ? 0 : site.getVersion();
        String revision = revision(data, version, assets.values());
        return new Snapshot(data, version, List.copyOf(assets.values()), revision);
    }

    public Path extractedAssetPath(Path extractionRoot, AssetDescriptor descriptor) {
        if (extractionRoot == null || descriptor == null || descriptor.path() == null) {
            throw BusinessException.badRequest("导入图片路径无效");
        }
        Path realRoot;
        try {
            realRoot = extractionRoot.toRealPath();
        } catch (IOException exception) {
            throw BusinessException.badRequest("导入预检目录已失效");
        }
        Path file = realRoot.resolve(descriptor.path()).normalize();
        if (!file.startsWith(realRoot) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw BusinessException.badRequest("导入图片文件已失效");
        }
        return file;
    }

    private AssetReference resolveAsset(String url, Map<String, SnapshotAsset> assets) {
        if (url == null || url.isBlank() || !url.startsWith(managedUrlPrefix)) {
            return new AssetReference(null);
        }
        String filename = url.substring(managedUrlPrefix.length());
        if (!MANAGED_FILENAME.matcher(filename).matches()) {
            throw unavailable("站点配置引用了不符合受管规则的背景图片");
        }
        Path configuredBackgroundDirectory = uploadRoot.resolve("backgrounds").normalize();
        Path file = configuredBackgroundDirectory.resolve(filename).normalize();
        if (!configuredBackgroundDirectory.equals(file.getParent()) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable("站点配置引用的背景图片不存在或不是普通文件");
        }
        try {
            long bytes = Files.size(file);
            String sha256 = sha256(file);
            String extension = filename.endsWith(".png") ? "png" : "jpg";
            String key = "asset-" + sha256;
            String mediaType = "png".equals(extension) ? "image/png" : "image/jpeg";
            assets.putIfAbsent(key, new SnapshotAsset(
                    new AssetDescriptor(
                            key,
                            "assets/" + key + "." + extension,
                            sha256,
                            bytes,
                            mediaType
                    ),
                    file,
                    extension
            ));
            return new AssetReference(key);
        } catch (IOException exception) {
            throw unavailable("无法读取站点配置引用的背景图片");
        }
    }

    private String revision(PortableData data, int siteVersion, java.util.Collection<SnapshotAsset> assets) {
        try {
            Map<String, Object> revisionData = new LinkedHashMap<>();
            revisionData.put("siteVersion", siteVersion);
            revisionData.put("data", data);
            revisionData.put("assets", assets.stream().map(SnapshotAsset::descriptor).toList());
            return sha256(objectMapper.writeValueAsBytes(revisionData));
        } catch (IOException exception) {
            throw new IllegalStateException("无法计算业务数据版本", exception);
        }
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    public static String sha256(byte[] bytes) {
        return hex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private String categoryKey(Long id) {
        return "category-" + id;
    }

    private String bookmarkKey(Long id) {
        return "bookmark-" + id;
    }

    private String searchEngineKey(Long id) {
        return "search-engine-" + id;
    }

    private String customLinkKey(Long id) {
        return "custom-link-" + id;
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    private record AssetReference(String assetKey) {
    }

    public record SnapshotAsset(AssetDescriptor descriptor, Path path, String extension) {
    }

    public record Snapshot(
            PortableData data,
            int siteVersion,
            List<SnapshotAsset> assets,
            String revision
    ) {
    }
}
