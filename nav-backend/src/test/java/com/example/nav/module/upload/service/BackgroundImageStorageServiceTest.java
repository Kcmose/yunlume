package com.example.nav.module.upload.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.upload.config.UploadStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackgroundImageStorageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final String DESKTOP_FILE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg";
    private static final String MOBILE_FILE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png";
    private static final String ORPHAN_FILE = "cccccccccccccccccccccccccccccccc.jpg";

    @TempDir
    Path tempDirectory;

    @Test
    void protectsCurrentDesktopAndMobileReferencesAndDeletesOldOrphan() throws Exception {
        Path desktop = managedFile(DESKTOP_FILE, "desktop", NOW.minusSeconds(120));
        Path mobile = managedFile(MOBILE_FILE, "mobile", NOW.minusSeconds(120));
        Path orphan = managedFile(ORPHAN_FILE, "orphan", NOW.minusSeconds(120));
        SiteConfig config = siteConfig(
                "/uploads/backgrounds/" + DESKTOP_FILE,
                "/uploads/backgrounds/" + MOBILE_FILE
        );
        BackgroundImageStorageService service = service(properties(60_000, 100, 1024), List.of(config));

        BackgroundImageStorageService.CleanupResult result = service.cleanupOrphans();

        assertTrue(Files.exists(desktop));
        assertTrue(Files.exists(mobile));
        assertFalse(Files.exists(orphan));
        assertEquals(2, result.referenced());
        assertEquals(1, result.deleted());
        assertFalse(result.skipped());
    }

    @Test
    void keepsUnreferencedFileDuringGracePeriod() throws Exception {
        Path fresh = managedFile(ORPHAN_FILE, "fresh", NOW.minusSeconds(30));
        BackgroundImageStorageService service = service(
                properties(60_000, 100, 1024), List.of(siteConfig("", "")));

        BackgroundImageStorageService.CleanupResult result = service.cleanupOrphans();

        assertTrue(Files.exists(fresh));
        assertEquals(1, result.graceProtected());
        assertEquals(0, result.deleted());
    }

    @Test
    void referenceLookupFailureSkipsCleanupWithoutDeletingFiles() throws Exception {
        Path orphan = managedFile(ORPHAN_FILE, "orphan", NOW.minusSeconds(120));
        UploadStorageProperties properties = properties(60_000, 100, 1024);
        SiteConfigMapper mapper = mock(SiteConfigMapper.class);
        when(mapper.selectAllForUpdate()).thenThrow(new IllegalStateException("database unavailable"));
        BackgroundImageStorageService service = new BackgroundImageStorageService(
                properties,
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        BackgroundImageStorageService.CleanupResult result = service.cleanupOrphans();

        assertTrue(result.skipped());
        assertTrue(Files.exists(orphan));
        assertEquals(0, result.deleted());
    }

    @Test
    void missingSiteConfigSkipsCleanupWithoutDeletingFiles() throws Exception {
        Path orphan = managedFile(ORPHAN_FILE, "orphan", NOW.minusSeconds(120));
        BackgroundImageStorageService service = service(properties(60_000, 100, 1024), List.of());

        BackgroundImageStorageService.CleanupResult result = service.cleanupOrphans();

        assertTrue(result.skipped());
        assertTrue(Files.exists(orphan));
        assertEquals(0, result.deleted());
    }

    @Test
    void externalAndTraversalLikeReferencesCannotProtectOrDeleteFilesOutsideStorage() throws Exception {
        Path localOrphan = managedFile(ORPHAN_FILE, "orphan", NOW.minusSeconds(120));
        Path outside = tempDirectory.resolve("outside.jpg");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        SiteConfig config = siteConfig(
                "https://cdn.example.test/uploads/backgrounds/" + ORPHAN_FILE,
                "/uploads/backgrounds/../../outside.jpg"
        );
        BackgroundImageStorageService service = service(properties(60_000, 100, 1024), List.of(config));

        BackgroundImageStorageService.CleanupResult result = service.cleanupOrphans();

        assertFalse(Files.exists(localOrphan));
        assertTrue(Files.exists(outside));
        assertEquals(0, result.referenced());
        assertEquals(1, result.deleted());
    }

    @Test
    void cleanupNeverFollowsManagedNameSymlink() throws Exception {
        Path outside = tempDirectory.resolve("outside-target.jpg");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(outside, FileTime.from(NOW.minusSeconds(120)));
        Path backgrounds = backgroundDirectory();
        Path link = backgrounds.resolve(ORPHAN_FILE);
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "当前文件系统不支持符号链接测试");
        }
        BackgroundImageStorageService service = service(
                properties(60_000, 100, 1024), List.of(siteConfig("", "")));

        service.cleanupOrphans();

        assertTrue(Files.exists(link));
        assertTrue(Files.exists(outside));
        assertEquals("outside", Files.readString(outside, StandardCharsets.UTF_8));
    }

    @Test
    void referencedAndGracePeriodFilesBothCountTowardFileQuota() throws Exception {
        managedFile(DESKTOP_FILE, "referenced", NOW.minusSeconds(120));
        managedFile(MOBILE_FILE, "grace", NOW.minusSeconds(30));
        SiteConfig config = siteConfig("/uploads/backgrounds/" + DESKTOP_FILE, "");
        BackgroundImageStorageService service = service(properties(60_000, 2, 1024), List.of(config));
        MockMultipartFile pending = new MockMultipartFile(
                "file", "pending.jpg", "image/jpeg", "pending".getBytes(StandardCharsets.UTF_8));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.store(pending, ORPHAN_FILE)
        );

        assertEquals(507, exception.getStatus().value());
        assertFalse(Files.exists(backgroundDirectory().resolve(ORPHAN_FILE)));
    }

    @Test
    void existingManagedBytesCountTowardTotalCapacityQuota() throws Exception {
        managedFile(DESKTOP_FILE, "123456", NOW.minusSeconds(120));
        SiteConfig config = siteConfig("/uploads/backgrounds/" + DESKTOP_FILE, "");
        UploadStorageProperties properties = properties(60_000, 10, 10);
        properties.setMaxBytes(10);
        BackgroundImageStorageService service = service(properties, List.of(config));
        MockMultipartFile pending = new MockMultipartFile(
                "file", "pending.jpg", "image/jpeg", "12345".getBytes(StandardCharsets.UTF_8));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.store(pending, ORPHAN_FILE)
        );

        assertEquals(507, exception.getStatus().value());
    }

    @Test
    void oldOrphanIsRemovedBeforeQuotaIsEvaluated() throws Exception {
        managedFile(DESKTOP_FILE, "old", NOW.minusSeconds(120));
        BackgroundImageStorageService service = service(
                properties(60_000, 1, 1024), List.of(siteConfig("", "")));
        MockMultipartFile pending = new MockMultipartFile(
                "file", "pending.jpg", "image/jpeg", "new".getBytes(StandardCharsets.UTF_8));

        BackgroundImageStorageService.StoredImage stored = service.store(pending, ORPHAN_FILE);

        assertFalse(Files.exists(backgroundDirectory().resolve(DESKTOP_FILE)));
        assertTrue(Files.exists(backgroundDirectory().resolve(ORPHAN_FILE)));
        assertEquals("/uploads/backgrounds/" + ORPHAN_FILE, stored.url());
    }

    @Test
    void rejectsTraversalFilenameBeforeWritingAnything() {
        BackgroundImageStorageService service = service(
                properties(60_000, 10, 1024), List.of(siteConfig("", "")));
        MockMultipartFile pending = new MockMultipartFile(
                "file", "pending.jpg", "image/jpeg", "new".getBytes(StandardCharsets.UTF_8));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.store(pending, "../outside.jpg")
        );

        assertEquals(400, exception.getStatus().value());
        assertFalse(Files.exists(tempDirectory.resolve("outside.jpg")));
    }

    private BackgroundImageStorageService service(
            UploadStorageProperties properties,
            List<SiteConfig> configs
    ) {
        SiteConfigMapper mapper = mock(SiteConfigMapper.class);
        when(mapper.selectAllForUpdate()).thenReturn(configs);
        return new BackgroundImageStorageService(
                properties,
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private UploadStorageProperties properties(long graceMs, int maxFiles, long maxTotalBytes) {
        UploadStorageProperties properties = new UploadStorageProperties();
        properties.setDirectory(tempDirectory.toString());
        properties.setBaseUrl("/uploads");
        properties.setMaxBytes(Math.min(1024, maxTotalBytes));
        properties.setMaxTotalBytes(maxTotalBytes);
        properties.setMaxFiles(maxFiles);
        properties.setOrphanGraceMs(graceMs);
        return properties;
    }

    private SiteConfig siteConfig(String desktop, String mobile) {
        SiteConfig config = new SiteConfig();
        config.setBackgroundImage(desktop);
        config.setMobileBackgroundImage(mobile);
        return config;
    }

    private Path managedFile(String filename, String content, Instant modifiedAt) throws Exception {
        Path file = backgroundDirectory().resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt));
        return file;
    }

    private Path backgroundDirectory() throws IOException {
        Path result = tempDirectory.resolve("backgrounds");
        Files.createDirectories(result);
        return result;
    }
}
