package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels.PreviewResponse;
import com.example.nav.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.example.nav.module.datapackage.model.PortablePackageModels.JobStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:portable_preview_expiry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PortableDataPackageExpiryTest {

    @Autowired
    private PortablePackageWriter packageWriter;
    @Autowired
    private PortablePackageReader packageReader;
    @Autowired
    private PortableDataSnapshotService snapshotService;
    @Autowired
    private PortableImportTransactionService transactionService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PortableImportJobStore jobStore;
    @Autowired
    private PortableImportCommitStore commitStore;

    @Test
    void previewTokenExpiresAfterFifteenMinutes(@TempDir Path temporary) {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-12T00:00:00Z"));
        PortableDataPackageService service = new PortableDataPackageService(
                packageWriter,
                packageReader,
                snapshotService,
                transactionService,
                userMapper,
                objectMapper,
                new SyncTaskExecutor(),
                jobStore,
                commitStore,
                clock,
                temporary.resolve("previews")
        );
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "admin",
                "unused",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        byte[] archive = packageWriter.exportPackage().bytes();
        PreviewResponse preview = service.preview(
                new MockMultipartFile("file", "portable.zip", "application/zip", archive),
                authentication
        );
        assertNotNull(preview.previewToken());

        clock.advance(Duration.ofMinutes(15));
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.confirm(preview.previewToken(), authentication)
        );
        assertEquals(404, exception.getStatus().value());
    }

    @Test
    void unconfirmedLocalPreviewSurvivesServiceReconstruction(@TempDir Path temporary) {
        Clock clock = Clock.systemUTC();
        var jobs = new InMemoryPortableImportJobStore(clock);
        var first = service(snapshotService, new SyncTaskExecutor(), jobs, clock, temporary);
        var auth = UsernamePasswordAuthenticationToken.authenticated("admin", "unused", List.of());
        var preview = first.preview(new MockMultipartFile("file", "portable.zip", "application/zip",
                packageWriter.exportPackage().bytes()), auth);
        var restarted = service(snapshotService, new SyncTaskExecutor(), jobs, clock, temporary);
        String jobId = restarted.confirm(preview.previewToken(), auth).jobId();
        assertEquals(JobStage.COMPLETED, restarted.job(jobId, auth).stage());
    }

    @Test
    void rejectedExecutorHasRecoverableFailureAndReleasesPreviewCapacity(@TempDir Path temporary) {
        Clock clock = Clock.systemUTC();
        var jobs = new InMemoryPortableImportJobStore(clock);
        var service = service(snapshotService, task -> { throw new org.springframework.core.task.TaskRejectedException("full"); },
                jobs, clock, temporary);
        var auth = UsernamePasswordAuthenticationToken.authenticated("admin", "unused", List.of());
        byte[] archive = packageWriter.exportPackage().bytes();
        var preview = service.preview(new MockMultipartFile("file", "portable.zip", "application/zip", archive), auth);
        assertEquals(503, assertThrows(BusinessException.class, () -> service.confirm(preview.previewToken(), auth)).getStatus().value());
        assertEquals(JobStage.FAILED, service.queryByPreviewToken(preview.previewToken(), auth).stage());
        assertNotNull(service.preview(new MockMultipartFile("file", "portable.zip", "application/zip", archive), auth).previewToken());
    }

    @Test
    void blockedPrecheckKeepsItsBudgetPastDeadlineUntilWorkspaceIsReleased(@TempDir Path temporary) throws Exception {
        var clock = new MutableClock(Instant.now());
        var captured = snapshotService.capture();
        var blockedSnapshots = mock(PortableDataSnapshotService.class);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        when(blockedSnapshots.capture()).thenAnswer(call -> {
            entered.countDown();
            assertTrue(proceed.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return captured;
        });
        var store = new FilePortablePreviewStore(objectMapper, clock, temporary.resolve("stored"), 1, Long.MAX_VALUE);
        var service = new PortableDataPackageService(packageWriter, packageReader, blockedSnapshots, transactionService,
                userMapper, objectMapper, new SyncTaskExecutor(), new InMemoryPortableImportJobStore(clock), commitStore,
                clock, temporary, store);
        var auth = UsernamePasswordAuthenticationToken.authenticated("admin", "unused", List.of());
        byte[] archive = packageWriter.exportPackage().bytes();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> service.preview(new MockMultipartFile(
                    "file", "portable.zip", "application/zip", archive), auth));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            clock.advance(Duration.ofMinutes(16));
            service.cleanupExpired();
            service.renewActiveImportLease();
            assertEquals(429, assertThrows(BusinessException.class, () -> store.reserve(
                    PortablePreviewStoreTest.token(), 1, archive.length, clock.instant().plusSeconds(900))).getStatus().value());
            proceed.countDown();
            var ready = pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(Instant.ofEpochMilli(clock.millis() + Duration.ofMinutes(15).toMillis()), ready.expiresAt());
            assertEquals(429, assertThrows(BusinessException.class, () -> store.reserve(
                    PortablePreviewStoreTest.token(), 1, archive.length, clock.instant().plusSeconds(900))).getStatus().value());
            service.confirm(ready.previewToken(), auth);
            assertNotNull(store.reserve(PortablePreviewStoreTest.token(), 1, archive.length, clock.instant().plusSeconds(900)));
        } finally { proceed.countDown(); executor.shutdownNow(); }
    }

    @Test
    void workspaceCreationFailureWithoutResidueImmediatelyReleasesQuota(@TempDir Path temporary) throws Exception {
        var clock = Clock.systemUTC();
        Path node = java.nio.file.Files.createDirectory(temporary.resolve("node"));
        java.nio.file.Files.writeString(node.resolve("work"), "not a directory");
        var store = new FilePortablePreviewStore(objectMapper, clock, temporary.resolve("stored"), 1, Long.MAX_VALUE);
        var service = new PortableDataPackageService(packageWriter, packageReader, snapshotService, transactionService,
                userMapper, objectMapper, new SyncTaskExecutor(), new InMemoryPortableImportJobStore(clock), commitStore,
                clock, node, store);
        var auth = UsernamePasswordAuthenticationToken.authenticated("admin", "unused", List.of());
        byte[] archive = packageWriter.exportPackage().bytes();
        assertEquals(503, assertThrows(BusinessException.class, () -> service.preview(
                new MockMultipartFile("file", "portable.zip", "application/zip", archive), auth)).getStatus().value());
        assertNotNull(store.reserve(PortablePreviewStoreTest.token(), 1, archive.length, clock.instant().plusSeconds(900)));
    }

    private PortableDataPackageService service(PortableDataSnapshotService snapshots,
            org.springframework.core.task.TaskExecutor executor, PortableImportJobStore jobs, Clock clock, Path root) {
        return new PortableDataPackageService(packageWriter, packageReader, snapshots, transactionService, userMapper,
                objectMapper, executor, jobs, commitStore, clock, root);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
