package com.example.nav.module.datapackage.service;

import com.example.nav.module.datapackage.model.PortablePackageModels.JobStage;
import com.example.nav.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实导入事务暂停于提交前，仅控制 Redis 外部状态和时间。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:portable_import_lease_expiry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "nav.upload.cleanup-initial-delay-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PortableImportLeaseExpiryIntegrationTest {
    @Autowired PortablePackageWriter writer;
    @Autowired PortablePackageReader reader;
    @Autowired PortableDataSnapshotService snapshots;
    @Autowired PortableImportTransactionService transaction;
    @Autowired PortableImportCommitStore commits;
    @Autowired UserMapper users;
    @Autowired ObjectMapper mapper;
    @TempDir Path temporary;

    @Test void expiredLeaseKeepsPollingUntilTheActualDatabaseCommit() throws Exception {
        var clock = new ProbeClock(Instant.now());
        var redisBoundary = new ControlledRedis();
        var redisJobs = new RedisPortableImportJobStore(redisBoundary.client, mapper, clock);
        var reached = new CountDownLatch(1);
        var continueImport = new CountDownLatch(1);
        var jobs = new PausingStore(redisJobs, reached, continueImport);
        var worker = Executors.newSingleThreadExecutor();
        var task = new AtomicReference<Future<?>>();
        var service = new PortableDataPackageService(writer, reader, snapshots, transaction, users,
                mapper, runnable -> task.set(worker.submit(runnable)), jobs, commits, clock, temporary);
        var auth = UsernamePasswordAuthenticationToken.authenticated("admin", "unused", List.of());
        int versionBefore = snapshots.capture().siteVersion();
        var preview = service.preview(new MockMultipartFile("file", "portable.zip", "application/zip",
                writer.exportPackage().bytes()), auth);
        try {
            String jobId = service.confirm(preview.previewToken(), auth).jobId();
            assertTrue(reached.await(20, TimeUnit.SECONDS));
            assertTrue(commits.findByJobId(jobId).isEmpty(), "transaction is paused before commit");
            // 等同于实例停顿超过2分钟、Redis锁TTL先到期；不等待真实181秒。
            clock.now = clock.now.plusSeconds(181);
            redisBoundary.expireLease();
            var observed = service.queryByPreviewToken(preview.previewToken(), auth);
            assertEquals(JobStage.VERIFYING, observed.stage());
            assertNull(observed.error());
            assertNull(observed.finishedAt());
            assertTrue(observed.message().contains("结果暂时无法确认"));
            assertEquals(JobStage.VERIFYING, service.job(jobId, auth).stage());
            assertEquals(JobStage.VERIFYING, service.currentJob(auth).stage());
            assertTrue(commits.findByJobId(jobId).isEmpty());
            continueImport.countDown();
            task.get().get(20, TimeUnit.SECONDS);
            assertTrue(commits.findByJobId(jobId).isPresent(), "same task commits after an uncertain response");
            assertEquals(versionBefore + 1, snapshots.capture().siteVersion());
            assertEquals(JobStage.COMPLETED, service.queryByPreviewToken(preview.previewToken(), auth).stage());
            assertEquals(JobStage.COMPLETED, service.job(jobId, auth).stage());
        } finally {
            continueImport.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    private static final class ProbeClock extends Clock {
        volatile Instant now;
        ProbeClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private record PausingStore(PortableImportJobStore delegate, CountDownLatch reached,
                               CountDownLatch proceed) implements PortableImportJobStore {
        public ClaimResult claim(StoredJob job) { return delegate.claim(job); }
        public Optional<StoredJob> findByPreviewToken(String token) { return delegate.findByPreviewToken(token); }
        public Optional<StoredJob> findJob(String id) { return delegate.findJob(id); }
        public Optional<StoredJob> findCurrent(long userId) { return delegate.findCurrent(userId); }
        public void save(Lease lease, StoredJob job) {
            delegate.save(lease, job);
            if (job.stage() == JobStage.VERIFYING) {
                reached.countDown();
                try { assertTrue(proceed.await(25, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
        }
        public boolean heartbeat(Lease lease) { return delegate.heartbeat(lease); }
        public void requireCurrent(Lease lease) { delegate.requireCurrent(lease); }
        public void release(Lease lease) { delegate.release(lease); }
        public void abandon(Lease lease, StoredJob job) { delegate.abandon(lease, job); }
    }

    /** 只模拟 Redis 外部存储和已读取的生产 Lua 合约；所有 Java 决策与 DB 事务为真实实现。 */
    private static final class ControlledRedis {
        final Map<String,String> data = new ConcurrentHashMap<>();
        final StringRedisTemplate client;
        @SuppressWarnings({"unchecked", "rawtypes"}) ControlledRedis() {
            ValueOperations<String,String> values = mock(ValueOperations.class);
            when(values.get(anyString())).thenAnswer(call -> data.get(call.getArgument(0)));
            client = mock(StringRedisTemplate.class, call -> {
                if (call.getMethod().getName().equals("opsForValue")) return values;
                if (!call.getMethod().getName().equals("execute")) return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
                Object[] raw = call.getRawArguments();
                RedisScript<?> script = (RedisScript<?>) raw[0];
                List<String> keys = (List<String>) raw[1];
                Object[] args = (Object[]) raw[2];
                if (script == RedisPortableImportScripts.CLAIM) {
                    if (data.containsKey(keys.get(0))) return -2L;
                    if (data.containsKey(keys.get(3))) return -3L;
                    long fence = Long.parseLong(data.getOrDefault(keys.get(4), "0")) + 1;
                    data.put(keys.get(4), Long.toString(fence));
                    data.put(keys.get(0), args[0].toString()); data.put(keys.get(1), args[1].toString());
                    data.put(keys.get(2), args[0].toString()); data.put(keys.get(3), args[0]+":"+fence);
                    return fence;
                }
                if (script == RedisPortableImportScripts.SAVE) {
                    if (!Objects.equals(data.get(keys.get(3)), args[2])) return 0L;
                    data.put(keys.get(0), args[0].toString()); data.put(keys.get(1), args[3].toString());
                    data.put(keys.get(2), args[3].toString()); return 1L;
                }
                if (script == RedisPortableImportScripts.RELEASE) {
                    return data.remove(keys.get(0), args[0]) ? 1L : 0L;
                }
                if (script == RedisPortableImportScripts.HEARTBEAT || script == RedisPortableImportScripts.REQUIRE_CURRENT) {
                    return Objects.equals(data.get(keys.get(0)), args[0]) ? 1L : 0L;
                }
                throw new AssertionError("Unexpected Redis operation");
            });
        }
        void expireLease() { data.remove(RedisPortableImportJobStore.lockKey()); }
    }
}
