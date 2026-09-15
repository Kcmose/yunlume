package com.example.nav.module.upload.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.service.PortableDataSnapshotService;
import com.example.nav.module.datapackage.service.PortablePackageReader;
import com.example.nav.module.datapackage.service.PortablePackageWriter;
import com.example.nav.module.site.dto.SiteConfigUpdateDTO;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.site.service.SiteConfigService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManagedBackgroundEquivalentPathIntegrationTest {
    private static final Path ROOT = createRoot();
    private static final String DESKTOP_FILE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png";
    private static final String MOBILE_FILE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png";
    private static final String PREFIX = "/uploads/backgrounds/";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:background_equivalent_paths;"
                + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=0");
        registry.add("nav.upload.directory", ROOT::toString);
        registry.add("nav.upload.base-url", () -> "/uploads");
        registry.add("nav.upload.orphan-grace-ms", () -> "0");
        registry.add("nav.upload.cleanup-initial-delay-ms", () -> "3600000");
    }

    @Autowired SiteConfigMapper mapper;
    @Autowired SiteConfigService sites;
    @Autowired BackgroundImageStorageService storage;
    @Autowired PortableDataSnapshotService snapshots;
    @Autowired PortablePackageWriter writer;
    @Autowired PortablePackageReader reader;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Validator validator;

    @BeforeEach
    void reset() throws IOException {
        mapper.update(null, Wrappers.<SiteConfig>lambdaUpdate().eq(SiteConfig::getId, 1L)
                .set(SiteConfig::getBackgroundType, "color")
                .set(SiteConfig::getBackgroundImage, "")
                .set(SiteConfig::getMobileBackgroundImage, "")
                .set(SiteConfig::getVersion, 0));
        deleteChildren(ROOT);
    }

    @AfterAll
    static void cleanup() throws IOException {
        deleteChildren(ROOT);
        Files.deleteIfExists(ROOT);
    }

    @ParameterizedTest
    @ValueSource(strings = {PREFIX, "/uploads/backgrounds/./", "/uploads/backgrounds/%2e/",
            "/uploads/./backgrounds/", "/uploads/%2E/backgrounds/",
            "/uploads/backgrounds/../backgrounds/", "/uploads/backgrounds/%2e%2e/backgrounds/"})
    void desktopAndMobileAliasesSurviveGcAndKeepBothAssetsInThePortablePackage(String prefix) throws Exception {
        Path desktop = image(DESKTOP_FILE);
        Path mobile = image(MOBILE_FILE);
        String desktopUrl = prefix + DESKTOP_FILE + "?v=1#desktop";
        String mobileUrl = prefix + MOBILE_FILE + "?v=2#mobile";
        SiteConfigUpdateDTO request = request(desktopUrl, mobileUrl, 0);
        assertTrue(validator.validate(request).isEmpty());
        sites.update(request);
        assertConfig(desktopUrl, mobileUrl, 1);

        // 导入失败后的补偿删除与定时 GC 必须采用相同的引用识别规则。
        storage.deleteImportedAssets(List.of(
                new BackgroundImageStorageService.ImportedAsset("desktop", DESKTOP_FILE,
                        PREFIX + DESKTOP_FILE, Files.size(desktop)),
                new BackgroundImageStorageService.ImportedAsset("mobile", MOBILE_FILE,
                        PREFIX + MOBILE_FILE, Files.size(mobile))));
        var cleanup = storage.cleanupOrphans();
        assertFalse(cleanup.skipped());
        assertEquals(2, cleanup.referenced());
        assertEquals(0, cleanup.deleted());
        assertTrue(Files.exists(desktop));
        assertTrue(Files.exists(mobile));

        var snapshot = snapshots.capture();
        assertEquals(desktopUrl, snapshot.data().siteConfig().backgroundImage());
        assertEquals(mobileUrl, snapshot.data().siteConfig().mobileBackgroundImage());
        assertEquals(Set.of(desktop, mobile), snapshot.assets().stream()
                .map(PortableDataSnapshotService.SnapshotAsset::path).collect(java.util.stream.Collectors.toSet()));
        String desktopKey = snapshot.data().siteConfig().backgroundImageAssetKey();
        String mobileKey = snapshot.data().siteConfig().mobileBackgroundImageAssetKey();
        assertNotNull(desktopKey);
        assertNotNull(mobileKey);
        assertNotEquals(desktopKey, mobileKey);

        Path archive = Files.write(ROOT.resolve("backup.zip"), writer.exportPackage().bytes());
        Path extracted = ROOT.resolve("extracted");
        var parsed = reader.read(archive, extracted);
        assertTrue(parsed.valid(), parsed.errors().toString());
        assertEquals(desktopUrl, parsed.data().siteConfig().backgroundImage());
        assertEquals(mobileUrl, parsed.data().siteConfig().mobileBackgroundImage());
        assertEquals(desktopKey, parsed.data().siteConfig().backgroundImageAssetKey());
        assertEquals(mobileKey, parsed.data().siteConfig().mobileBackgroundImageAssetKey());
        assertEquals(2, parsed.assetsByKey().size());
        assertArrayEquals(Files.readAllBytes(desktop),
                Files.readAllBytes(extracted.resolve(parsed.assetsByKey().get(desktopKey).path())));
        assertArrayEquals(Files.readAllBytes(mobile),
                Files.readAllBytes(extracted.resolve(parsed.assetsByKey().get(mobileKey).path())));
    }

    @ParameterizedTest
    @MethodSource("missingReferences")
    void missingAliasesRejectWithoutChangingConfigAndCanRetryAfterRestoringTheFile(
            String prefix, boolean mobile) throws Exception {
        image(DESKTOP_FILE);
        String original = PREFIX + DESKTOP_FILE;
        sites.update(request(original, original, 0));
        String missing = prefix + MOBILE_FILE + "?restored=1#background";
        SiteConfigUpdateDTO update = request(mobile ? original : missing, mobile ? missing : original, 1);
        assertTrue(validator.validate(update).isEmpty());

        BusinessException failure = assertThrows(BusinessException.class, () -> sites.update(update));
        assertEquals(400, failure.getStatus().value());
        assertConfig(original, original, 1);

        image(MOBILE_FILE);
        sites.update(update);
        assertConfig(update.backgroundImage(), update.mobileBackgroundImage(), 2);
        assertEquals(2, storage.cleanupOrphans().referenced());
        assertEquals(2, snapshots.capture().assets().size());
    }

    static Stream<Arguments> missingReferences() {
        return Stream.of(PREFIX, "/uploads/backgrounds/./", "/uploads/%2e/backgrounds/",
                        "/uploads/backgrounds/../backgrounds/", "/uploads/backgrounds/%2e%2e/backgrounds/")
                .flatMap(prefix -> Stream.of(Arguments.of(prefix, false), Arguments.of(prefix, true)));
    }

    @Test
    void rolledBackReplacementKeepsTheOriginalAliasAndCommittedRetryAllowsItsCollection() throws Exception {
        Path originalFile = image(DESKTOP_FILE);
        String original = "/uploads/%2e/backgrounds/" + DESKTOP_FILE;
        sites.update(request(original, "", 0));
        Path replacementFile = image(MOBILE_FILE);
        String replacement = "/uploads/backgrounds/%2e%2e/backgrounds/" + MOBILE_FILE;
        SiteConfigUpdateDTO replacementRequest = request(replacement, "", 1);

        new TransactionTemplate(transactionManager).execute(status -> {
            sites.update(replacementRequest);
            status.setRollbackOnly();
            return null;
        });
        assertConfig(original, "", 1);
        var rollbackCleanup = storage.cleanupOrphans();
        assertEquals(1, rollbackCleanup.referenced());
        assertEquals(1, rollbackCleanup.deleted());
        assertTrue(Files.exists(originalFile));
        assertFalse(Files.exists(replacementFile));

        image(MOBILE_FILE);
        sites.update(replacementRequest);
        assertConfig(replacement, "", 2);
        var committedCleanup = storage.cleanupOrphans();
        assertEquals(1, committedCleanup.referenced());
        assertEquals(1, committedCleanup.deleted());
        assertFalse(Files.exists(originalFile));
        assertTrue(Files.exists(replacementFile));
    }

    private void assertConfig(String desktop, String mobile, int version) {
        SiteConfig config = mapper.selectById(1L);
        assertEquals(desktop, config.getBackgroundImage());
        assertEquals(mobile, config.getMobileBackgroundImage());
        assertEquals(version, config.getVersion());
    }

    private Path image(String filename) throws IOException {
        Path file = Files.createDirectories(ROOT.resolve("backgrounds")).resolve(filename);
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, filename.equals(DESKTOP_FILE) ? 0x123456 : 0xabcdef);
        assertTrue(ImageIO.write(image, "png", file.toFile()));
        Files.setLastModifiedTime(file, FileTime.from(Instant.EPOCH));
        return file;
    }

    private SiteConfigUpdateDTO request(String desktop, String mobile, int version) {
        return new SiteConfigUpdateDTO(null, null, null, "image", null, desktop, mobile,
                null, null, null, null, null, null, null, version);
    }

    private static Path createRoot() {
        try {
            return Files.createTempDirectory("background-equivalent-paths-").toAbsolutePath();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void deleteChildren(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(directory)) Files.deleteIfExists(path);
            }
        }
    }
}
