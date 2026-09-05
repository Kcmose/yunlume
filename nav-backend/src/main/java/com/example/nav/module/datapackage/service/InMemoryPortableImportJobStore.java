package com.example.nav.module.datapackage.service;

import com.example.nav.module.datapackage.model.PortablePackageModels.Issue;
import com.example.nav.module.datapackage.model.PortablePackageModels.JobStage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnExpression("'${spring.cache.type:simple}' != 'redis'")
class InMemoryPortableImportJobStore implements PortableImportJobStore {

    private static final Duration JOB_TTL = Duration.ofHours(24);
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final Clock clock;
    private final Map<String, Entry> jobs = new HashMap<>();
    private final Map<String, String> jobsByPreview = new HashMap<>();
    private final Map<Long, String> currentByUser = new HashMap<>();
    private Lease lease;
    private long fencingSequence;

    InMemoryPortableImportJobStore() {
        this(Clock.systemUTC());
    }

    InMemoryPortableImportJobStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized ClaimResult claim(StoredJob job) {
        cleanup();
        if (jobsByPreview.containsKey(job.previewToken())) {
            return new ClaimResult(ClaimOutcome.PREVIEW_ALREADY_CLAIMED, null);
        }
        if (lease != null) return new ClaimResult(ClaimOutcome.IMPORT_RUNNING, null);
        Instant expiresAt = clock.instant().plus(JOB_TTL);
        jobs.put(job.jobId(), new Entry(job, expiresAt));
        jobsByPreview.put(job.previewToken(), job.jobId());
        currentByUser.put(job.userId(), job.jobId());
        lease = new Lease(job.jobId(), ++fencingSequence);
        return new ClaimResult(ClaimOutcome.CREATED, lease);
    }

    @Override
    public synchronized Optional<StoredJob> findByPreviewToken(String previewToken) {
        cleanup();
        String jobId = jobsByPreview.get(previewToken);
        return jobId == null ? Optional.empty() : findJob(jobId);
    }

    @Override
    public synchronized Optional<StoredJob> findJob(String jobId) {
        cleanup();
        Entry entry = jobs.get(jobId);
        if (entry == null) return Optional.empty();
        StoredJob job = entry.job();
        if (isStale(job) && (lease == null || !jobId.equals(lease.ownerId()))) {
            Instant now = clock.instant();
            job = new StoredJob(
                    job.jobId(), job.previewToken(), job.userId(), JobStage.FAILED,
                    job.createdAt(), job.startedAt(), now,
                    "导入失败：服务实例中断，任务未能继续",
                    new Issue("IMPORT_INTERRUPTED", null, "导入服务实例中断，请确认当前数据后重新预检"),
                    now
            );
            jobs.put(jobId, new Entry(job, now.plus(JOB_TTL)));
        }
        return Optional.of(job);
    }

    @Override
    public synchronized Optional<StoredJob> findCurrent(long userId) {
        cleanup();
        String jobId = currentByUser.get(userId);
        return jobId == null ? Optional.empty() : findJob(jobId);
    }

    @Override
    public synchronized void save(Lease expected, StoredJob job) {
        requireCurrent(expected);
        jobs.put(job.jobId(), new Entry(job, clock.instant().plus(JOB_TTL)));
        jobsByPreview.put(job.previewToken(), job.jobId());
        currentByUser.put(job.userId(), job.jobId());
    }

    @Override
    public synchronized boolean heartbeat(Lease expected) {
        return expected.equals(lease);
    }

    @Override
    public synchronized void requireCurrent(Lease expected) {
        if (!expected.equals(lease)) {
            throw com.example.nav.common.exception.BusinessException.conflict(
                    "导入任务的本地状态租约已失效");
        }
    }

    @Override
    public synchronized void release(Lease expected) {
        if (expected.equals(lease)) lease = null;
    }

    @Override
    public synchronized void abandon(Lease expected, StoredJob job) {
        if (!expected.equals(lease)) return;
        jobs.remove(job.jobId());
        jobsByPreview.remove(job.previewToken(), job.jobId());
        currentByUser.remove(job.userId(), job.jobId());
        release(expected);
    }

    private boolean isStale(StoredJob job) {
        return job.stage() != JobStage.COMPLETED
                && job.stage() != JobStage.FAILED
                && (job.heartbeatAt() == null || !job.heartbeatAt().plus(STALE_AFTER).isAfter(clock.instant()));
    }

    private void cleanup() {
        Instant now = clock.instant();
        jobs.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt().isAfter(now)) return false;
            StoredJob job = entry.getValue().job();
            jobsByPreview.remove(job.previewToken(), job.jobId());
            currentByUser.remove(job.userId(), job.jobId());
            if (lease != null && job.jobId().equals(lease.ownerId())) lease = null;
            return true;
        });
    }

    private record Entry(StoredJob job, Instant expiresAt) {
    }
}
