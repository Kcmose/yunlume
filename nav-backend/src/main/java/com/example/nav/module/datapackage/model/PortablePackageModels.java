package com.example.nav.module.datapackage.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PortablePackageModels {

    public static final int FORMAT_VERSION = 1;
    public static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;
    public static final long MAX_EXPANDED_BYTES = 64L * 1024 * 1024;
    public static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    public static final int MAX_ENTRIES = 100;

    private PortablePackageModels() {
    }

    public record Manifest(
            int formatVersion,
            Instant exportedAt,
            String generator,
            Integer sourceSiteVersion,
            FileDescriptor data,
            List<AssetDescriptor> assets
    ) {
    }

    public record FileDescriptor(String path, String sha256, long bytes) {
    }

    public record AssetDescriptor(
            String key,
            String path,
            String sha256,
            long bytes,
            String mediaType
    ) {
    }

    public record PortableData(
            SiteConfigData siteConfig,
            List<CategoryData> categories,
            List<BookmarkData> bookmarks,
            List<SearchEngineData> searchEngines,
            List<CustomLinkData> customLinks
    ) {
    }

    public record SiteConfigData(
            String key,
            String siteName,
            String siteDescription,
            String publishUrl,
            String backgroundType,
            String backgroundColor,
            String backgroundImage,
            String backgroundImageAssetKey,
            String mobileBackgroundImage,
            String mobileBackgroundImageAssetKey,
            String fontColor,
            Boolean backgroundEffect,
            Boolean musicEnabled,
            String musicUrl,
            Boolean subscribeEnabled,
            Boolean topContentEnabled,
            String messageText
    ) {
    }

    public record CategoryData(
            String key,
            String name,
            String icon,
            Integer sortOrder,
            Boolean visible
    ) {
    }

    public record BookmarkData(
            String key,
            String categoryKey,
            String name,
            String url,
            String icon,
            String description,
            Integer sortOrder,
            Boolean recommend,
            Boolean external,
            Boolean visible
    ) {
    }

    public record SearchEngineData(
            String key,
            String name,
            String icon,
            String searchUrl,
            String placeholder,
            Boolean defaultEngine,
            Integer sortOrder,
            Boolean visible
    ) {
    }

    public record CustomLinkData(
            String key,
            String title,
            String url,
            String position,
            Integer sortOrder,
            Boolean visible
    ) {
    }

    public record Issue(String code, String path, String message) {
    }

    public record ResourceCounts(
            int siteConfigs,
            int categories,
            int bookmarks,
            int searchEngines,
            int customLinks,
            int assets
    ) {
    }

    public record CountsComparison(ResourceCounts current, ResourceCounts imported) {
    }

    public record DiffCounts(int added, int updated, int deleted, int unchanged) {
        public DiffCounts plus(DiffCounts other) {
            return new DiffCounts(
                    added + other.added,
                    updated + other.updated,
                    deleted + other.deleted,
                    unchanged + other.unchanged
            );
        }
    }

    public record DiffSummary(
            DiffCounts siteConfigs,
            DiffCounts categories,
            DiffCounts bookmarks,
            DiffCounts searchEngines,
            DiffCounts customLinks,
            DiffCounts assets,
            DiffCounts total
    ) {
    }

    public record PackageInfo(
            int formatVersion,
            Instant exportedAt,
            String generator,
            String archiveSha256
    ) {
    }

    public record PreviewResponse(
            String previewToken,
            Instant expiresAt,
            PackageInfo packageInfo,
            CountsComparison counts,
            DiffSummary diff,
            List<Issue> errors,
            List<Issue> warnings
    ) {
    }

    public record ConfirmResponse(String jobId) {
    }

    public enum JobStage {
        PREPARING,
        WRITING,
        VERIFYING,
        COMPLETED,
        FAILED
    }

    public record JobResponse(
            String jobId,
            JobStage stage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            String message,
            Issue error
    ) {
    }

    public record ParsedPackage(
            Manifest manifest,
            PortableData data,
            Map<String, AssetDescriptor> assetsByKey,
            List<Issue> errors,
            List<Issue> warnings,
            String archiveSha256
    ) {
        public boolean valid() {
            return errors != null && errors.isEmpty();
        }
    }
}
