package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels.JobStage;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortableDataPackageRestartRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    private PortableImportJobStore jobStore;
    private PortableImportCommitStore commitStore;
    private UserMapper userMapper;
    private PortableDataPackageService service;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp(@TempDir Path temporary) {
        jobStore = mock(PortableImportJobStore.class);
        commitStore = mock(PortableImportCommitStore.class);
        userMapper = mock(UserMapper.class);
        User admin = new User();
        admin.setId(42L);
        admin.setUsername("admin");
        admin.setRole("admin");
        admin.setStatus(true);
        when(userMapper.selectOne(any())).thenReturn(admin);
        service = new PortableDataPackageService(
                mock(PortablePackageWriter.class),
                mock(PortablePackageReader.class),
                mock(PortableDataSnapshotService.class),
                mock(PortableImportTransactionService.class),
                userMapper,
                new ObjectMapper().findAndRegisterModules(),
                new SyncTaskExecutor(),
                jobStore,
                commitStore,
                Clock.fixed(NOW, ZoneOffset.UTC),
                temporary.resolve("previews")
        );
        authentication = UsernamePasswordAuthenticationToken.authenticated("admin", "unused", java.util.List.of());
    }

    @Test
    void repeatedConfirmationAfterRestartReturnsPersistedJobIdWithoutLocalPreview() {
        PortableImportJobStore.StoredJob persisted = completedJob("job-after-lost-response", "preview-token");
        when(jobStore.findByPreviewToken("preview-token")).thenReturn(Optional.of(persisted));

        assertEquals("job-after-lost-response", service.confirm("preview-token", authentication).jobId());
    }

    @Test
    void readOnlyTokenQueryReturnsEveryStoredStageAndDoesNotClaimOrImport() {
        for (JobStage stage : JobStage.values()) {
            var stored = new PortableImportJobStore.StoredJob("known", "token", 42L, stage,
                    NOW, NOW, stage == JobStage.FAILED || stage == JobStage.COMPLETED ? NOW : null,
                    "status", null, NOW);
            when(jobStore.findByPreviewToken("token")).thenReturn(Optional.of(stored));
            assertEquals(stage, service.queryByPreviewToken("token", authentication).stage());
        }
        org.mockito.Mockito.verify(jobStore, org.mockito.Mockito.never()).claim(any());
        org.mockito.Mockito.verify(jobStore, org.mockito.Mockito.never()).save(any(), any());
    }

    @Test
    void readOnlyTokenQueryPrefersDatabaseTruthAndConcealsMissingOrOtherOwner() {
        when(commitStore.findByPreviewToken("preview-committed")).thenReturn(Optional.of(committedMarker()));
        assertEquals(JobStage.COMPLETED, service.queryByPreviewToken("preview-committed", authentication).stage());
        org.mockito.Mockito.verify(jobStore, org.mockito.Mockito.never()).findByPreviewToken("preview-committed");
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.queryByPreviewToken("unknown", authentication)).getStatus().value());
        var other = new PortableImportJobStore.StoredJob("hidden", "other", 99L, JobStage.FAILED,
                NOW, NOW, NOW, "hidden", null, NOW);
        when(jobStore.findByPreviewToken("other")).thenReturn(Optional.of(other));
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.queryByPreviewToken("other", authentication)).getStatus().value());
        org.mockito.Mockito.verify(jobStore, org.mockito.Mockito.never()).claim(any());
    }

    @Test
    void jobCanBeQueriedAfterServiceRestart() {
        PortableImportJobStore.StoredJob persisted = completedJob("job-after-restart", "preview-token-2");
        when(jobStore.findJob("job-after-restart")).thenReturn(Optional.of(persisted));

        assertEquals(JobStage.COMPLETED, service.job("job-after-restart", authentication).stage());
    }

    @Test
    void currentRunningJobCanBeRecoveredAfterServiceRestart() {
        PortableImportJobStore.StoredJob persisted = new PortableImportJobStore.StoredJob(
                "current-job", "preview-token-3", 42L, JobStage.WRITING,
                NOW.minusSeconds(10), NOW.minusSeconds(9), null,
                "正在导入", null, NOW.minusSeconds(1));
        when(jobStore.findCurrent(42L)).thenReturn(Optional.of(persisted));

        assertEquals("current-job", service.currentJob(authentication).jobId());
    }

    @Test
    void completedJobIsNotReportedAsCurrentIndefinitely() {
        when(jobStore.findCurrent(42L)).thenReturn(Optional.of(completedJob("old-job", "old-preview")));
        when(commitStore.findCurrent(42L)).thenReturn(Optional.of(committedMarker()));

        assertThrows(BusinessException.class, () -> service.currentJob(authentication));
    }

    @Test
    void durableDatabaseMarkerOverridesAStaleRedisFailure() {
        PortableImportJobStore.StoredJob staleFailure = new PortableImportJobStore.StoredJob(
                "committed-job", "preview-committed", 42L, JobStage.FAILED,
                NOW.minusSeconds(10), NOW.minusSeconds(9), NOW.minusSeconds(1),
                "导入失败；事务未提交，数据库写入已回滚", null, NOW.minusSeconds(1));
        when(jobStore.findJob("committed-job")).thenReturn(Optional.of(staleFailure));
        when(commitStore.findByJobId("committed-job")).thenReturn(Optional.of(committedMarker()));

        assertEquals(JobStage.COMPLETED, service.job("committed-job", authentication).stage());
    }

    @Test
    void jobLookupSurvivesRedisLossAfterDatabaseCommit() {
        when(jobStore.findJob("committed-job")).thenReturn(Optional.empty());
        when(commitStore.findByJobId("committed-job")).thenReturn(Optional.of(committedMarker()));

        assertEquals(JobStage.COMPLETED, service.job("committed-job", authentication).stage());
    }

    private PortableImportCommitStore.CommittedImport committedMarker() {
        return new PortableImportCommitStore.CommittedImport(
                "committed-job", "preview-committed", 42L,
                NOW.minusSeconds(10), NOW.minusSeconds(9), NOW.minusSeconds(1), 3);
    }

    private PortableImportJobStore.StoredJob completedJob(String jobId, String previewToken) {
        return new PortableImportJobStore.StoredJob(
                jobId,
                previewToken,
                42L,
                JobStage.COMPLETED,
                NOW.minusSeconds(10),
                NOW.minusSeconds(9),
                NOW.minusSeconds(1),
                "导入完成",
                null,
                NOW.minusSeconds(1)
        );
    }
}
