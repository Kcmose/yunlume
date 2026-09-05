package com.example.nav.module.datapackage.service;

import com.example.nav.common.validation.SafeUrlRules;
import com.example.nav.module.datapackage.model.PortablePackageModels.AssetDescriptor;
import com.example.nav.module.datapackage.model.PortablePackageModels.BookmarkData;
import com.example.nav.module.datapackage.model.PortablePackageModels.CategoryData;
import com.example.nav.module.datapackage.model.PortablePackageModels.CustomLinkData;
import com.example.nav.module.datapackage.model.PortablePackageModels.Issue;
import com.example.nav.module.datapackage.model.PortablePackageModels.PortableData;
import com.example.nav.module.datapackage.model.PortablePackageModels.SearchEngineData;
import com.example.nav.module.datapackage.model.PortablePackageModels.SiteConfigData;
import com.example.nav.module.upload.config.UploadStorageProperties;
import com.example.nav.module.upload.service.ManagedBackgroundReferences;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
final class PortableDataValidator {

    private static final int MAX_RESOURCE_ITEMS = 10_000;
    private static final int MAX_ISSUES = 200;
    private static final Pattern STABLE_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private final ManagedBackgroundReferences backgroundReferences;
    private final long maxAssetBytes;

    PortableDataValidator(UploadStorageProperties properties) {
        this.backgroundReferences = new ManagedBackgroundReferences(properties);
        this.maxAssetBytes = properties.getMaxBytes();
    }

    ValidationResult validate(PortableData data, Map<String, AssetDescriptor> assets) {
        Collector collector = new Collector();
        if (data == null) {
            collector.error("DATA_REQUIRED", "data.json", "业务数据不能为空");
            return collector.result();
        }

        validateSite(data.siteConfig(), assets, collector);
        validateAssetStorageLimits(assets, collector);
        List<CategoryData> categories = safeList(data.categories(), "categories", collector);
        List<BookmarkData> bookmarks = safeList(data.bookmarks(), "bookmarks", collector);
        List<SearchEngineData> searchEngines = safeList(data.searchEngines(), "searchEngines", collector);
        List<CustomLinkData> customLinks = safeList(data.customLinks(), "customLinks", collector);

        Set<String> categoryKeys = validateCategories(categories, collector);
        validateBookmarks(bookmarks, categoryKeys, collector);
        validateSearchEngines(searchEngines, collector);
        validateCustomLinks(customLinks, collector);
        warnUnusedAssets(data.siteConfig(), assets, collector);
        return collector.result();
    }

    private void validateAssetStorageLimits(Map<String, AssetDescriptor> assets, Collector collector) {
        for (Map.Entry<String, AssetDescriptor> entry : assets.entrySet()) {
            AssetDescriptor descriptor = entry.getValue();
            if (descriptor != null && (descriptor.bytes() <= 0 || descriptor.bytes() > maxAssetBytes)) {
                collector.error(
                        "ASSET_STORAGE_LIMIT",
                        "manifest.assets." + entry.getKey(),
                        "背景图片超过当前服务器单图存储限制"
                );
            }
        }
    }

    private void validateSite(SiteConfigData site, Map<String, AssetDescriptor> assets, Collector collector) {
        if (site == null) {
            collector.error("SITE_CONFIG_REQUIRED", "siteConfig", "站点配置不能为空");
            return;
        }
        key(site.key(), "siteConfig.key", collector);
        requiredText(site.siteName(), 50, "siteConfig.siteName", collector);
        optionalText(site.siteDescription(), 255, "siteConfig.siteDescription", collector);
        optionalText(site.publishUrl(), 255, "siteConfig.publishUrl", collector);
        optionalSafeUrl(site.publishUrl(), "siteConfig.publishUrl", collector);
        if (!"color".equals(site.backgroundType()) && !"image".equals(site.backgroundType())) {
            collector.error("BACKGROUND_TYPE", "siteConfig.backgroundType", "背景类型只能是 color 或 image");
        }
        color(site.backgroundColor(), "siteConfig.backgroundColor", collector);
        color(site.fontColor(), "siteConfig.fontColor", collector);
        optionalText(site.backgroundImage(), 500, "siteConfig.backgroundImage", collector);
        optionalText(site.mobileBackgroundImage(), 500, "siteConfig.mobileBackgroundImage", collector);
        optionalText(site.musicUrl(), 500, "siteConfig.musicUrl", collector);
        optionalSafeUrl(site.musicUrl(), "siteConfig.musicUrl", collector);
        optionalText(site.messageText(), 100, "siteConfig.messageText", collector);
        requiredBoolean(site.backgroundEffect(), "siteConfig.backgroundEffect", collector);
        requiredBoolean(site.musicEnabled(), "siteConfig.musicEnabled", collector);
        requiredBoolean(site.subscribeEnabled(), "siteConfig.subscribeEnabled", collector);
        requiredBoolean(site.topContentEnabled(), "siteConfig.topContentEnabled", collector);

        validateBackgroundReference(
                site.backgroundImage(), site.backgroundImageAssetKey(), assets,
                "siteConfig.backgroundImage", collector);
        validateBackgroundReference(
                site.mobileBackgroundImage(), site.mobileBackgroundImageAssetKey(), assets,
                "siteConfig.mobileBackgroundImage", collector);
        if ("image".equals(site.backgroundType())
                && blank(site.backgroundImage()) && blank(site.backgroundImageAssetKey())) {
            collector.error("BACKGROUND_REQUIRED", "siteConfig.backgroundImage", "图片背景模式必须配置 PC 背景图");
        }
    }

    private Set<String> validateCategories(List<CategoryData> categories, Collector collector) {
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < categories.size() && collector.accepting(); index++) {
            CategoryData category = categories.get(index);
            String path = "categories[" + index + "]";
            if (category == null) {
                collector.error("CATEGORY_REQUIRED", path, "分类不能为空");
                continue;
            }
            key(category.key(), path + ".key", collector);
            if (category.key() != null && !keys.add(category.key())) {
                collector.error("DUPLICATE_KEY", path + ".key", "分类稳定标识重复");
            }
            requiredText(category.name(), 50, path + ".name", collector);
            optionalText(category.icon(), 100, path + ".icon", collector);
            nonNegative(category.sortOrder(), path + ".sortOrder", collector);
            requiredBoolean(category.visible(), path + ".visible", collector);
        }
        return keys;
    }

    private void validateBookmarks(
            List<BookmarkData> bookmarks,
            Set<String> categoryKeys,
            Collector collector
    ) {
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < bookmarks.size() && collector.accepting(); index++) {
            BookmarkData bookmark = bookmarks.get(index);
            String path = "bookmarks[" + index + "]";
            if (bookmark == null) {
                collector.error("BOOKMARK_REQUIRED", path, "书签不能为空");
                continue;
            }
            key(bookmark.key(), path + ".key", collector);
            if (bookmark.key() != null && !keys.add(bookmark.key())) {
                collector.error("DUPLICATE_KEY", path + ".key", "书签稳定标识重复");
            }
            key(bookmark.categoryKey(), path + ".categoryKey", collector);
            if (bookmark.categoryKey() != null && !categoryKeys.contains(bookmark.categoryKey())) {
                collector.error("CATEGORY_REFERENCE", path + ".categoryKey", "书签引用的分类不存在");
            }
            requiredText(bookmark.name(), 100, path + ".name", collector);
            requiredHttpUrl(bookmark.url(), 500, path + ".url", collector);
            optionalText(bookmark.icon(), 255, path + ".icon", collector);
            optionalText(bookmark.description(), 255, path + ".description", collector);
            nonNegative(bookmark.sortOrder(), path + ".sortOrder", collector);
            requiredBoolean(bookmark.recommend(), path + ".recommend", collector);
            requiredBoolean(bookmark.external(), path + ".external", collector);
            requiredBoolean(bookmark.visible(), path + ".visible", collector);
        }
    }

    private void validateSearchEngines(List<SearchEngineData> engines, Collector collector) {
        if (engines.isEmpty()) {
            collector.error("SEARCH_ENGINE_REQUIRED", "searchEngines", "至少需要一个搜索引擎");
            return;
        }
        Set<String> keys = new HashSet<>();
        int visibleDefaults = 0;
        for (int index = 0; index < engines.size() && collector.accepting(); index++) {
            SearchEngineData engine = engines.get(index);
            String path = "searchEngines[" + index + "]";
            if (engine == null) {
                collector.error("SEARCH_ENGINE_REQUIRED", path, "搜索引擎不能为空");
                continue;
            }
            key(engine.key(), path + ".key", collector);
            if (engine.key() != null && !keys.add(engine.key())) {
                collector.error("DUPLICATE_KEY", path + ".key", "搜索引擎稳定标识重复");
            }
            requiredText(engine.name(), 50, path + ".name", collector);
            optionalText(engine.icon(), 255, path + ".icon", collector);
            optionalText(engine.placeholder(), 100, path + ".placeholder", collector);
            nonNegative(engine.sortOrder(), path + ".sortOrder", collector);
            requiredBoolean(engine.defaultEngine(), path + ".defaultEngine", collector);
            requiredBoolean(engine.visible(), path + ".visible", collector);
            validateSearchUrl(engine.searchUrl(), path + ".searchUrl", collector);
            if (Boolean.TRUE.equals(engine.defaultEngine()) && !Boolean.TRUE.equals(engine.visible())) {
                collector.error("HIDDEN_DEFAULT_ENGINE", path, "默认搜索引擎必须处于启用状态");
            }
            if (Boolean.TRUE.equals(engine.defaultEngine()) && Boolean.TRUE.equals(engine.visible())) {
                visibleDefaults++;
            }
        }
        if (visibleDefaults != 1) {
            collector.error("DEFAULT_ENGINE_COUNT", "searchEngines", "必须且只能有一个已启用的默认搜索引擎");
        }
    }

    private void validateCustomLinks(List<CustomLinkData> links, Collector collector) {
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < links.size() && collector.accepting(); index++) {
            CustomLinkData link = links.get(index);
            String path = "customLinks[" + index + "]";
            if (link == null) {
                collector.error("CUSTOM_LINK_REQUIRED", path, "自定义链接不能为空");
                continue;
            }
            key(link.key(), path + ".key", collector);
            if (link.key() != null && !keys.add(link.key())) {
                collector.error("DUPLICATE_KEY", path + ".key", "自定义链接稳定标识重复");
            }
            requiredText(link.title(), 50, path + ".title", collector);
            validateCustomUrl(link.url(), path + ".url", collector);
            if (!"header".equals(link.position()) && !"footer".equals(link.position())) {
                collector.error("CUSTOM_LINK_POSITION", path + ".position", "位置只能是 header 或 footer");
            }
            nonNegative(link.sortOrder(), path + ".sortOrder", collector);
            requiredBoolean(link.visible(), path + ".visible", collector);
        }
    }

    private void validateBackgroundReference(
            String url,
            String assetKey,
            Map<String, AssetDescriptor> assets,
            String path,
            Collector collector
    ) {
        if (!blank(assetKey)) {
            key(assetKey, path + "AssetKey", collector);
            if (!assets.containsKey(assetKey)) {
                collector.error("ASSET_REFERENCE", path + "AssetKey", "引用的背景图片资产不存在");
            }
        }
        if (!blank(url) && !safeHttpOrInternal(url)) {
            collector.error("BACKGROUND_URL", path, "背景图片必须是安全的 HTTP(S) 地址或站内绝对路径");
        }
        if (backgroundReferences.isManagedUrl(url) && blank(assetKey)) {
            collector.error("MANAGED_ASSET_MISSING", path, "受管背景图片必须随数据包携带资产");
        }
    }

    private void warnUnusedAssets(
            SiteConfigData site,
            Map<String, AssetDescriptor> assets,
            Collector collector
    ) {
        if (site == null) return;
        Set<String> referenced = new HashSet<>();
        if (!blank(site.backgroundImageAssetKey())) referenced.add(site.backgroundImageAssetKey());
        if (!blank(site.mobileBackgroundImageAssetKey())) referenced.add(site.mobileBackgroundImageAssetKey());
        for (String key : assets.keySet()) {
            if (!referenced.contains(key)) {
                collector.warning("UNUSED_ASSET", "manifest.assets", "资产 " + key + " 未被站点背景引用，将不会导入");
            }
        }
    }

    private <T> List<T> safeList(List<T> values, String path, Collector collector) {
        if (values == null) {
            collector.error("LIST_REQUIRED", path, "列表不能为空");
            return List.of();
        }
        if (values.size() > MAX_RESOURCE_ITEMS) {
            collector.error("LIST_TOO_LARGE", path, "单类资源不能超过 " + MAX_RESOURCE_ITEMS + " 项");
            return values.subList(0, Math.min(values.size(), MAX_RESOURCE_ITEMS));
        }
        return values;
    }

    private void validateSearchUrl(String value, String path, Collector collector) {
        if (!requiredText(value, 500, path, collector)) return;
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            if (!"keyword".equals(matcher.group(1))) {
                collector.error("SEARCH_PLACEHOLDER", path, "搜索地址只支持 {keyword} 占位符");
                return;
            }
        }
        int authorityStart = value.indexOf("://") + 3;
        int authorityEnd = value.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int index = value.indexOf(delimiter, Math.max(0, authorityStart));
            if (index >= 0 && index < authorityEnd) authorityEnd = index;
        }
        if (authorityStart < 3 || value.substring(authorityStart, authorityEnd).contains("{")) {
            collector.error("SEARCH_URL", path, "搜索占位符不能出现在主机名中");
            return;
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0 && value.substring(fragment).contains("{keyword}")) {
            collector.error("SEARCH_URL", path, "搜索占位符不能出现在 URL 片段中");
            return;
        }
        String normalized = value.replace("{keyword}", "keyword");
        if (!safeHttp(normalized)) {
            collector.error("SEARCH_URL", path, "搜索地址必须是安全的 HTTP(S) URL");
        }
    }

    private void validateCustomUrl(String value, String path, Collector collector) {
        if (!requiredText(value, 500, path, collector)) return;
        if (value.startsWith("#")) {
            try {
                URI uri = URI.create(value);
                if (uri.getScheme() == null && uri.getRawAuthority() == null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                        && uri.getRawQuery() == null && uri.getRawFragment() != null
                        && !uri.getRawFragment().isBlank()) {
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Report the common safe URL error below.
            }
        } else if (safeHttpOrInternal(value)) {
            return;
        }
        collector.error("CUSTOM_LINK_URL", path, "链接必须是安全的 HTTP(S) 地址、站内路径或锚点");
    }

    private void requiredHttpUrl(String value, int max, String path, Collector collector) {
        if (requiredText(value, max, path, collector) && !safeHttp(value)) {
            collector.error("HTTP_URL", path, "地址必须是安全的完整 HTTP(S) URL");
        }
    }

    private void optionalSafeUrl(String value, String path, Collector collector) {
        if (!blank(value) && !safeHttpOrInternal(value)) {
            collector.error("SAFE_URL", path, "地址必须是安全的 HTTP(S) 地址或站内绝对路径");
        }
    }

    private boolean safeHttpOrInternal(String value) {
        return SafeUrlRules.isSafeHttpOrInternal(value);
    }

    private boolean safeHttp(String value) {
        return SafeUrlRules.isSafeHttp(value);
    }

    private void key(String value, String path, Collector collector) {
        if (value == null || !STABLE_KEY.matcher(value).matches()) {
            collector.error("STABLE_KEY", path, "稳定标识格式无效");
        }
    }

    private boolean requiredText(String value, int max, String path, Collector collector) {
        if (value == null || value.trim().isEmpty()) {
            collector.error("REQUIRED", path, "字段不能为空");
            return false;
        }
        if (value.length() > max) {
            collector.error("TOO_LONG", path, "字段不能超过 " + max + " 个字符");
            return false;
        }
        return true;
    }

    private void optionalText(String value, int max, String path, Collector collector) {
        if (value != null && value.length() > max) {
            collector.error("TOO_LONG", path, "字段不能超过 " + max + " 个字符");
        }
    }

    private void color(String value, String path, Collector collector) {
        if (value == null || !COLOR.matcher(value).matches()) {
            collector.error("COLOR", path, "颜色必须是六位十六进制值");
        }
    }

    private void nonNegative(Integer value, String path, Collector collector) {
        if (value == null || value < 0) {
            collector.error("SORT_ORDER", path, "排序值不能为空且不能小于 0");
        }
    }

    private void requiredBoolean(Boolean value, String path, Collector collector) {
        if (value == null) collector.error("BOOLEAN_REQUIRED", path, "布尔字段不能为空");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record ValidationResult(List<Issue> errors, List<Issue> warnings) {
    }

    private static final class Collector {
        private final java.util.ArrayList<Issue> errors = new java.util.ArrayList<>();
        private final java.util.ArrayList<Issue> warnings = new java.util.ArrayList<>();

        private void error(String code, String path, String message) {
            if (accepting()) errors.add(new Issue(code, path, message));
        }

        private void warning(String code, String path, String message) {
            if (warnings.size() < MAX_ISSUES) warnings.add(new Issue(code, path, message));
        }

        private boolean accepting() {
            return errors.size() < MAX_ISSUES;
        }

        private ValidationResult result() {
            if (!accepting()) {
                warnings.add(new Issue("ISSUE_LIMIT", "data.json", "错误数量过多，仅返回前 200 项"));
            }
            return new ValidationResult(List.copyOf(errors), List.copyOf(warnings));
        }
    }
}
