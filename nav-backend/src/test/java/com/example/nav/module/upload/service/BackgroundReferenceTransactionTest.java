package com.example.nav.module.upload.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels.ParsedPackage;
import com.example.nav.module.datapackage.service.PortableDataSnapshotService;
import com.example.nav.module.datapackage.service.PortableImportTransactionService;
import com.example.nav.module.datapackage.service.PortablePackageReader;
import com.example.nav.module.datapackage.service.PortablePackageWriter;
import com.example.nav.module.site.dto.SiteConfigUpdateDTO;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.site.service.SiteConfigService;
import com.example.nav.module.upload.config.UploadStorageProperties;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackgroundReferenceTransactionTest {
    private static final Path ROOT = createRoot();
    private static final String FILENAME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png";
    private static final String URL = "/uploads/backgrounds/" + FILENAME;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:background_reference_transaction;"
                + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
                + "DB_CLOSE_DELAY=0;LOCK_TIMEOUT=10000");
        registry.add("nav.upload.directory", ROOT::toString);
        registry.add("nav.upload.orphan-grace-ms", () -> "0");
        registry.add("nav.upload.cleanup-initial-delay-ms", () -> "3600000");
    }

    @Autowired SiteConfigMapper mapper;
    @Autowired SiteConfigService siteService;
    @Autowired BackgroundImageStorageService storage;
    @Autowired UploadStorageProperties uploadProperties;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PortableDataSnapshotService snapshots;
    @Autowired PortablePackageWriter writer;
    @Autowired PortablePackageReader reader;
    @Autowired PortableImportTransactionService importer;
    @Autowired Validator validator;
    private ExecutorService executor;
    private TransactionTemplate transactions;
    private BackgroundImageStorageService secondInstance;

    @BeforeEach
    void reset() throws IOException {
        mapper.update(null, Wrappers.<SiteConfig>lambdaUpdate().eq(SiteConfig::getId, 1L)
                .set(SiteConfig::getBackgroundType, "color")
                .set(SiteConfig::getBackgroundImage, "")
                .set(SiteConfig::getMobileBackgroundImage, "")
                .set(SiteConfig::getVersion, 0));
        deleteChildren(ROOT);
        executor = Executors.newFixedThreadPool(2);
        transactions = new TransactionTemplate(transactionManager);
        // 独立 storageLock，只有数据库行锁能够协调这两个实例。
        secondInstance = new BackgroundImageStorageService(uploadProperties, mapper);
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void removeFiles() throws IOException {
        deleteChildren(ROOT);
        Files.deleteIfExists(ROOT);
    }

    @Test
    void savingReferenceHoldsGcUntilCommitAcrossStorageInstances() throws Exception {
        Path image = image();
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> saving = executor.submit(() -> transactions.execute(status -> {
            siteService.update(request(URL));
            updated.countDown();
            await(commit);
            return null;
        }));
        await(updated);
        CountDownLatch gcAttempted = new CountDownLatch(1);
        Future<BackgroundImageStorageService.CleanupResult> gc = startGc(gcAttempted);
        try {
            await(gcAttempted);
            assertThrows(TimeoutException.class, () -> gc.get(200, TimeUnit.MILLISECONDS));
            assertTrue(Files.exists(image));
        } finally {
            commit.countDown();
        }
        saving.get(10, TimeUnit.SECONDS);
        assertEquals(1, gc.get(10, TimeUnit.SECONDS).referenced());
        assertTrue(Files.exists(image));
        assertEquals(URL, mapper.selectById(1L).getBackgroundImage());
    }

    @Test
    void gcWinningFirstMakesLateSaveRejectTheDeletedManagedFile() throws Exception {
        Path image = image();
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch releaseGc = new CountDownLatch(1);
        Future<?> gc = executor.submit(() -> transactions.execute(status -> {
            assertEquals(1, secondInstance.cleanupOrphans().deleted());
            deleted.countDown();
            await(releaseGc);
            return null;
        }));
        await(deleted);
        CountDownLatch saveAttempted = new CountDownLatch(1);
        Future<BusinessException> saving = executor.submit(() -> {
            saveAttempted.countDown();
            return assertThrows(BusinessException.class, () -> siteService.update(request(URL)));
        });
        try {
            await(saveAttempted);
            assertThrows(TimeoutException.class, () -> saving.get(200, TimeUnit.MILLISECONDS));
        } finally {
            releaseGc.countDown();
        }
        gc.get(10, TimeUnit.SECONDS);
        assertEquals(400, saving.get(10, TimeUnit.SECONDS).getStatus().value());
        assertFalse(Files.exists(image));
        assertEquals("", mapper.selectById(1L).getBackgroundImage());
        assertEquals(0, mapper.selectById(1L).getVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?v=1", "#desktop", "?v=1#desktop"})
    void suffixReferencesSurviveGcAndRoundTripThroughTheRealPackageFormat(String suffix) throws Exception {
        Path image = image();
        String url = URL + suffix;
        assertTrue(validator.validate(request(url)).isEmpty());
        siteService.update(request(url));

        assertEquals(1, storage.cleanupOrphans().referenced());
        assertTrue(Files.exists(image));
        PortableDataSnapshotService.Snapshot snapshot = snapshots.capture();
        assertEquals(url, snapshot.data().siteConfig().backgroundImage());
        assertEquals(1, snapshot.assets().size());
        assertEquals(image, snapshot.assets().get(0).path());

        ParsedPackage parsed = exportedPackage();
        assertTrue(parsed.valid(), parsed.errors().toString());
        assertEquals(url, parsed.data().siteConfig().backgroundImage());
        assertEquals(1, parsed.assetsByKey().size());
    }

    @Test
    void importedAssetsStayProtectedAfterCopyUntilTheirConfigCommits() throws Exception {
        image();
        siteService.update(request(URL));
        ParsedPackage parsed = exportedPackage();
        String revision = snapshots.capture().revision();
        CountDownLatch copied = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicReference<Path> importedImage = new AtomicReference<>();
        Future<?> importing = executor.submit(() -> replace(parsed, revision, () -> {
            Path file = referencedFile();
            importedImage.set(file);
            age(file);
            copied.countDown();
            await(commit);
        }));
        await(copied);
        CountDownLatch gcAttempted = new CountDownLatch(1);
        Future<BackgroundImageStorageService.CleanupResult> gc = startGc(gcAttempted);
        try {
            await(gcAttempted);
            assertThrows(TimeoutException.class, () -> gc.get(200, TimeUnit.MILLISECONDS));
        } finally {
            commit.countDown();
        }
        importing.get(10, TimeUnit.SECONDS);
        assertEquals(1, gc.get(10, TimeUnit.SECONDS).referenced());
        assertTrue(Files.exists(importedImage.get()));
        assertEquals(importedImage.get(), referencedFile());
    }

    @Test
    void failedImportCleansCopiedAssetsAfterRollbackWithoutWaitingOnItsOwnSiteLock() throws Exception {
        Path original = image();
        siteService.update(request(URL));
        ParsedPackage parsed = exportedPackage();
        String revision = snapshots.capture().revision();
        AtomicReference<Path> importedImage = new AtomicReference<>();
        Future<?> failed = executor.submit(() -> assertThrows(IllegalStateException.class,
                () -> replace(parsed, revision, () -> {
                    importedImage.set(referencedFile());
                    throw new IllegalStateException("force rollback after copying assets");
                })));
        failed.get(10, TimeUnit.SECONDS);
        assertNotNull(importedImage.get());
        assertFalse(Files.exists(importedImage.get()));
        assertTrue(Files.exists(original));
        assertEquals(URL, mapper.selectById(1L).getBackgroundImage());
    }

    @Test
    void rollbackCleanupDoesNotDeleteAnAssetNowReferencedByCommittedConfiguration() throws Exception {
        Path image = image();
        siteService.update(request(URL + "?keep=1"));
        storage.deleteImportedAssets(List.of(new BackgroundImageStorageService.ImportedAsset(
                "old-import", FILENAME, URL, Files.size(image))));
        assertTrue(Files.exists(image));
    }

    private Future<BackgroundImageStorageService.CleanupResult> startGc(CountDownLatch attempted) {
        return executor.submit(() -> transactions.execute(status -> {
            attempted.countDown();
            return secondInstance.cleanupOrphans();
        }));
    }

    private ParsedPackage exportedPackage() throws IOException {
        Path archive = ROOT.resolve("backup.zip");
        Files.write(archive, writer.exportPackage().bytes());
        return reader.read(archive, ROOT.resolve("extracted"));
    }

    private void replace(ParsedPackage parsed, String revision, Runnable beforeVerifying) {
        importer.replaceBusinessData(parsed, ROOT.resolve("extracted"), revision,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1L,
                Instant.now(), Instant.now(), null, beforeVerifying);
    }

    private Path referencedFile() {
        String filename = new ManagedBackgroundReferences(uploadProperties)
                .filename(mapper.selectById(1L).getBackgroundImage());
        assertNotNull(filename);
        return ROOT.resolve("backgrounds").resolve(filename);
    }

    private Path image() throws IOException {
        Path directory = Files.createDirectories(ROOT.resolve("backgrounds"));
        Path file = directory.resolve(FILENAME);
        assertTrue(ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "png", file.toFile()));
        age(file);
        return file;
    }

    private static void age(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.EPOCH));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SiteConfigUpdateDTO request(String url) {
        return new SiteConfigUpdateDTO(null, null, null, "image", null, url, null,
                null, null, null, null, null, null, null, 0);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "controlled transaction did not reach its boundary");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static Path createRoot() {
        try {
            return Files.createTempDirectory("background-reference-transaction-").toAbsolutePath();
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
