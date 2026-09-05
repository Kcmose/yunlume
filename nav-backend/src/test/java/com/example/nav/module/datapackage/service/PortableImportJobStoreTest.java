package com.example.nav.module.datapackage.service;

import com.example.nav.module.datapackage.model.PortablePackageModels.Issue;
import com.example.nav.module.datapackage.model.PortablePackageModels.JobStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PortableImportJobStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ObjectMapper objectMapper;
    private RedisPortableImportJobStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        store = new RedisPortableImportJobStore(redis, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void allRuntimeKeysUseTheProvisionedNavNamespace() {
        assertTrue(RedisPortableImportJobStore.previewKey("preview").startsWith("nav:"));
        assertTrue(RedisPortableImportJobStore.jobKey("job").startsWith("nav:"));
        assertTrue(RedisPortableImportJobStore.currentKey(1L).startsWith("nav:"));
        assertTrue(RedisPortableImportJobStore.lockKey().startsWith("nav:"));
        assertTrue(RedisPortableImportJobStore.fenceSequenceKey().startsWith("nav:"));
    }

    @Test
    void aJobWrittenByOneInstanceCanBeReadByAnotherInstance() throws Exception {
        PortableImportJobStore.StoredJob job = runningJob("job-1", "preview-1", 7L, NOW);
        when(values.get(RedisPortableImportJobStore.jobKey("job-1")))
                .thenReturn(objectMapper.writeValueAsString(job));

        PortableImportJobStore restartedStore = new RedisPortableImportJobStore(
                redis,
                objectMapper,
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC)
        );

        assertEquals(job, restartedStore.findJob("job-1").orElseThrow());
    }

    @Test
    void previewTokenMappingSurvivesAConfirmResponseLoss() throws Exception {
        PortableImportJobStore.StoredJob job = runningJob("job-2", "preview-2", 8L, NOW);
        when(values.get(RedisPortableImportJobStore.previewKey("preview-2"))).thenReturn("job-2");
        when(values.get(RedisPortableImportJobStore.jobKey("job-2")))
                .thenReturn(objectMapper.writeValueAsString(job));

        assertEquals(job, store.findByPreviewToken("preview-2").orElseThrow());
    }

    @Test
    void claimCreatesMonotonicFenceAndTokenBoundMutexAtomicallyWithTtl() {
        PortableImportJobStore.StoredJob job = runningJob("job-3", "preview-3", 9L, NOW);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(41L);

        PortableImportJobStore.ClaimResult claim = store.claim(job);

        assertEquals(PortableImportJobStore.ClaimOutcome.CREATED, claim.outcome());
        assertEquals(new PortableImportJobStore.Lease("job-3", 41L), claim.lease());

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DefaultRedisScript> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(
                script.capture(),
                eq(List.of(
                        RedisPortableImportJobStore.previewKey("preview-3"),
                        RedisPortableImportJobStore.jobKey("job-3"),
                        RedisPortableImportJobStore.currentKey(9L),
                        RedisPortableImportJobStore.lockKey(),
                        RedisPortableImportJobStore.fenceSequenceKey()
                )),
                eq("job-3"),
                any(String.class),
                eq(Long.toString(Duration.ofHours(24).toMillis())),
                eq(Long.toString(Duration.ofMinutes(2).toMillis()))
        );
        String lua = script.getValue().getScriptAsString();
        assertEquals(com.example.nav.common.redis.RedisProductionLua.script(
                com.example.nav.common.redis.RedisProductionLua.CLAIM, "claim"), lua);
    }

    @Test
    void staleNonTerminalJobIsAtomicallyChangedToFailedAfterAnInstanceDies() throws Exception {
        Instant staleHeartbeat = NOW.minus(Duration.ofMinutes(3));
        PortableImportJobStore.StoredJob stale = runningJob("job-4", "preview-4", 10L, staleHeartbeat);
        when(values.get(RedisPortableImportJobStore.jobKey("job-4")))
                .thenReturn(objectMapper.writeValueAsString(stale));
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);

        PortableImportJobStore.StoredJob recovered = store.findJob("job-4").orElseThrow();

        assertEquals(JobStage.FAILED, recovered.stage());
        assertEquals(NOW, recovered.finishedAt());
        assertEquals("IMPORT_INTERRUPTED", recovered.error().code());
        assertTrue(recovered.message().contains("服务实例中断"));
    }

    @Test
    void staleSnapshotRemainsRunningWhileItsOwnerStillHoldsTheLease() throws Exception {
        PortableImportJobStore.StoredJob stale = runningJob(
                "job-active", "preview-active", 14L, NOW.minusSeconds(121));
        when(values.get(RedisPortableImportJobStore.jobKey("job-active")))
                .thenReturn(objectMapper.writeValueAsString(stale));
        when(values.get(RedisPortableImportJobStore.lockKey())).thenReturn("job-active:12");

        PortableImportJobStore.StoredJob recovered = store.findJob("job-active").orElseThrow();

        assertEquals(JobStage.WRITING, recovered.stage());
        verify(redis, never()).execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    void currentJobUsesThePersistedPerAdministratorPointer() throws Exception {
        PortableImportJobStore.StoredJob job = runningJob("job-5", "preview-5", 11L, NOW);
        when(values.get(RedisPortableImportJobStore.currentKey(11L))).thenReturn("job-5");
        when(values.get(RedisPortableImportJobStore.jobKey("job-5")))
                .thenReturn(objectMapper.writeValueAsString(job));

        Optional<PortableImportJobStore.StoredJob> current = store.findCurrent(11L);

        assertEquals("job-5", current.orElseThrow().jobId());
    }

    @Test
    void heartbeatRenewsTheMutexBeforeAImportCanOutliveItsInitialLease() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(1L);

        assertTrue(store.heartbeat(new PortableImportJobStore.Lease("job-6", 17L)));

        verify(redis).execute(
                any(DefaultRedisScript.class),
                eq(List.of(RedisPortableImportJobStore.lockKey())),
                eq("job-6:17"),
                eq(Long.toString(Duration.ofMinutes(2).toMillis()))
        );
    }

    @Test
    void staleOwnerCannotOverwriteJobStateAfterItsMutexWasLost() {
        PortableImportJobStore.StoredJob job = runningJob("job-7", "preview-7", 13L, NOW);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(0L);

        assertThrows(com.example.nav.common.exception.BusinessException.class,
                () -> store.save(new PortableImportJobStore.Lease("job-7", 22L), job));
    }

    @Test
    void staleOwnerWithSameJobIdCannotPassAfterANewerFenceWasClaimed() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(0L);

        assertThrows(com.example.nav.common.exception.BusinessException.class,
                () -> store.requireCurrent(new PortableImportJobStore.Lease("job-reused", 8L)));

        verify(redis).execute(
                any(DefaultRedisScript.class),
                eq(List.of(RedisPortableImportJobStore.lockKey())),
                eq("job-reused:8")
        );
    }

    @Test
    void laterClaimGetsHigherFenceAndRejectsTheExpiredOwner() {
        InMemoryPortableImportJobStore local = new InMemoryPortableImportJobStore(
                Clock.fixed(NOW, ZoneOffset.UTC));
        PortableImportJobStore.ClaimResult first = local.claim(runningJob("old", "preview-old", 21L, NOW));
        local.release(first.lease());
        PortableImportJobStore.ClaimResult second = local.claim(runningJob("new", "preview-new", 22L, NOW));

        assertTrue(second.lease().fencingToken() > first.lease().fencingToken());
        assertThrows(com.example.nav.common.exception.BusinessException.class,
                () -> local.requireCurrent(first.lease()));
        local.requireCurrent(second.lease());
    }

    private PortableImportJobStore.StoredJob runningJob(
            String jobId,
            String previewToken,
            long userId,
            Instant heartbeat
    ) {
        return new PortableImportJobStore.StoredJob(
                jobId,
                previewToken,
                userId,
                JobStage.WRITING,
                NOW.minusSeconds(5),
                NOW,
                null,
                "正在事务性替换业务数据",
                (Issue) null,
                heartbeat
        );
    }
}
