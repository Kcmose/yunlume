package com.example.nav.module.datapackage.service;

import com.example.nav.module.datapackage.model.PortablePackageModels.JobStage;
import com.example.nav.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:post_commit_truth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PortableDataPackagePostCommitTruthTest {

    @Autowired PortablePackageWriter packageWriter;
    @Autowired PortablePackageReader packageReader;
    @Autowired PortableDataSnapshotService snapshotService;
    @Autowired PortableImportTransactionService transactionService;
    @Autowired UserMapper userMapper;
    @Autowired ObjectMapper objectMapper;
    @Autowired PortableImportCommitStore commitStore;

    @Test
    void completedRedisWriteFailureCannotTurnACommittedImportIntoFailed(@TempDir Path temporary) {
        FailingCompletedSaveStore jobs = new FailingCompletedSaveStore(
                new InMemoryPortableImportJobStore(Clock.systemUTC()));
        PortableDataPackageService service = new PortableDataPackageService(
                packageWriter, packageReader, snapshotService, transactionService,
                userMapper, objectMapper, new SyncTaskExecutor(), jobs, commitStore,
                Clock.systemUTC(), temporary.resolve("previews"));
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                "admin", "unused", java.util.List.of());
        byte[] archive = packageWriter.exportPackage().bytes();
        String preview = service.preview(new MockMultipartFile(
                "file", "portable.zip", "application/zip", archive), auth).previewToken();

        String jobId = service.confirm(preview, auth).jobId();

        assertEquals(JobStage.COMPLETED, service.job(jobId, auth).stage());
        assertEquals(1L, commitStore.findByJobId(jobId).stream().count());
    }

    @Test
    void releaseFailureStillDeletesPreviewFiles(@TempDir Path temporary) throws Exception {
        Path previews = temporary.resolve("previews");
        PortableDataPackageService service = new PortableDataPackageService(
                packageWriter, packageReader, snapshotService, transactionService,
                userMapper, objectMapper, new SyncTaskExecutor(),
                new FailingReleaseStore(new InMemoryPortableImportJobStore(Clock.systemUTC())),
                commitStore, Clock.systemUTC(), previews);
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                "admin", "unused", java.util.List.of());
        byte[] archive = packageWriter.exportPackage().bytes();
        String preview = service.preview(new MockMultipartFile(
                "file", "portable.zip", "application/zip", archive), auth).previewToken();

        service.confirm(preview, auth);

        try (var entries = Files.walk(previews)) {
            assertEquals(0L, entries.filter(path -> path.getFileName().toString().equals("package.zip")
                    || path.getFileName().toString().equals("preview.json")
                    || path.getFileName().toString().startsWith("work-")).count());
        }
    }

    private static final class FailingCompletedSaveStore implements PortableImportJobStore {
        private final PortableImportJobStore delegate;

        private FailingCompletedSaveStore(PortableImportJobStore delegate) {
            this.delegate = delegate;
        }

        public ClaimResult claim(StoredJob job) { return delegate.claim(job); }
        public Optional<StoredJob> findByPreviewToken(String token) { return delegate.findByPreviewToken(token); }
        public Optional<StoredJob> findJob(String id) { return delegate.findJob(id); }
        public Optional<StoredJob> findCurrent(long userId) { return delegate.findCurrent(userId); }
        public void save(Lease lease, StoredJob job) {
            if (job.stage() == JobStage.COMPLETED) throw new IllegalStateException("redis unavailable");
            delegate.save(lease, job);
        }
        public boolean heartbeat(Lease lease) { return delegate.heartbeat(lease); }
        public void requireCurrent(Lease lease) { delegate.requireCurrent(lease); }
        public void release(Lease lease) { delegate.release(lease); }
        public void abandon(Lease lease, StoredJob job) { delegate.abandon(lease, job); }
    }

    private static final class FailingReleaseStore implements PortableImportJobStore {
        private final PortableImportJobStore delegate;

        private FailingReleaseStore(PortableImportJobStore delegate) { this.delegate = delegate; }
        public ClaimResult claim(StoredJob job) { return delegate.claim(job); }
        public Optional<StoredJob> findByPreviewToken(String token) { return delegate.findByPreviewToken(token); }
        public Optional<StoredJob> findJob(String id) { return delegate.findJob(id); }
        public Optional<StoredJob> findCurrent(long userId) { return delegate.findCurrent(userId); }
        public void save(Lease lease, StoredJob job) { delegate.save(lease, job); }
        public boolean heartbeat(Lease lease) { return delegate.heartbeat(lease); }
        public void requireCurrent(Lease lease) { delegate.requireCurrent(lease); }
        public void release(Lease lease) {
            delegate.release(lease);
            throw new IllegalStateException("redis unavailable during release");
        }
        public void abandon(Lease lease, StoredJob job) { delegate.abandon(lease, job); }
    }
}
