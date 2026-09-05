package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels.JobStage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.http.HttpStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
@org.springframework.stereotype.Component
class RedisPortableImportJobStore implements PortableImportJobStore {

    private static final String PREFIX = "nav:portable-import:";
    private static final Duration JOB_TTL = Duration.ofHours(24);
    private static final Duration LOCK_TTL = Duration.ofMinutes(2);
    private static final Duration STALE_AFTER = LOCK_TTL;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    RedisPortableImportJobStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, Clock.systemUTC());
    }

    RedisPortableImportJobStore(StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ClaimResult claim(StoredJob job) {
        Long result = redis.execute(
                RedisPortableImportScripts.CLAIM,
                List.of(previewKey(job.previewToken()), jobKey(job.jobId()), currentKey(job.userId()), lockKey(),
                        fenceSequenceKey()),
                job.jobId(),
                json(job),
                Long.toString(JOB_TTL.toMillis()),
                Long.toString(LOCK_TTL.toMillis())
        );
        return ClaimResult.fromCode(job.jobId(), result);
    }

    public Optional<StoredJob> findByPreviewToken(String previewToken) {
        String jobId = redis.opsForValue().get(previewKey(previewToken));
        return jobId == null ? Optional.empty() : findJob(jobId);
    }

    public Optional<StoredJob> findJob(String jobId) {
        String value = redis.opsForValue().get(jobKey(jobId));
        if (value == null) return Optional.empty();
        StoredJob job = parse(value);
        if (isStale(job) && !ownsAnyFence(job.jobId(), redis.opsForValue().get(lockKey()))) {
            return Optional.of(job.awaitingOutcome());
        }
        return Optional.of(job);
    }

    public Optional<StoredJob> findCurrent(long userId) {
        String jobId = redis.opsForValue().get(currentKey(userId));
        return jobId == null ? Optional.empty() : findJob(jobId);
    }

    public void save(Lease lease, StoredJob job) {
        Long saved = redis.execute(
                RedisPortableImportScripts.SAVE,
                List.of(jobKey(job.jobId()), previewKey(job.previewToken()), currentKey(job.userId()), lockKey()),
                json(job),
                Long.toString(JOB_TTL.toMillis()),
                lease.lockValue(),
                job.jobId()
        );
        if (!Long.valueOf(1L).equals(saved)) {
            throw BusinessException.conflict("导入任务的 Redis 状态租约已失效");
        }
    }

    public boolean heartbeat(Lease lease) {
        Long result = redis.execute(
                RedisPortableImportScripts.HEARTBEAT,
                List.of(lockKey()),
                lease.lockValue(),
                Long.toString(LOCK_TTL.toMillis())
        );
        return Long.valueOf(1L).equals(result);
    }

    public void requireCurrent(Lease lease) {
        Long result = redis.execute(RedisPortableImportScripts.REQUIRE_CURRENT, List.of(lockKey()), lease.lockValue());
        if (!Long.valueOf(1L).equals(result)) {
            throw BusinessException.conflict("导入任务的 Redis 状态租约已失效");
        }
    }

    public void release(Lease lease) {
        redis.execute(RedisPortableImportScripts.RELEASE, List.of(lockKey()), lease.lockValue());
    }

    public void abandon(Lease lease, StoredJob job) {
        redis.execute(
                RedisPortableImportScripts.ABANDON,
                List.of(previewKey(job.previewToken()), jobKey(job.jobId()), currentKey(job.userId()), lockKey()),
                job.jobId(), lease.lockValue()
        );
    }

    private boolean isStale(StoredJob job) {
        return job.stage() != JobStage.COMPLETED
                && job.stage() != JobStage.FAILED
                && (job.heartbeatAt() == null || !job.heartbeatAt().plus(STALE_AFTER).isAfter(clock.instant()));
    }

    private String json(StoredJob job) {
        try {
            return objectMapper.writeValueAsString(job);
        } catch (JsonProcessingException exception) {
            throw unavailable("无法持久化导入任务状态", exception);
        }
    }

    private StoredJob parse(String value) {
        try {
            return objectMapper.readValue(value, StoredJob.class);
        } catch (JsonProcessingException exception) {
            throw unavailable("无法读取导入任务状态", exception);
        }
    }

    private BusinessException unavailable(String message, Exception cause) {
        BusinessException exception = new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, message);
        exception.initCause(cause);
        return exception;
    }

    static String previewKey(String token) {
        return PREFIX + "preview:" + token;
    }

    static String jobKey(String jobId) {
        return PREFIX + "job:" + jobId;
    }

    static String currentKey(long userId) {
        return PREFIX + "current:" + userId;
    }

    static String lockKey() {
        return PREFIX + "lock";
    }

    static String fenceSequenceKey() {
        return PREFIX + "fence-sequence";
    }

    private boolean ownsAnyFence(String jobId, String lockValue) {
        return lockValue != null && lockValue.startsWith(jobId + ":");
    }

}
