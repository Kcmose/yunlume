package com.example.nav.module.datapackage.service;

import com.example.nav.module.datapackage.model.PortablePackageModels.Issue;
import com.example.nav.module.datapackage.model.PortablePackageModels.JobResponse;
import com.example.nav.module.datapackage.model.PortablePackageModels.JobStage;

import java.time.Instant;
import java.util.Optional;

interface PortableImportJobStore {

    ClaimResult claim(StoredJob job);

    Optional<StoredJob> findByPreviewToken(String previewToken);

    Optional<StoredJob> findJob(String jobId);

    Optional<StoredJob> findCurrent(long userId);

    void save(Lease lease, StoredJob job);

    boolean heartbeat(Lease lease);

    void requireCurrent(Lease lease);

    void release(Lease lease);

    void abandon(Lease lease, StoredJob job);

    enum ClaimOutcome {
        CREATED(1),
        PREVIEW_ALREADY_CLAIMED(-2),
        IMPORT_RUNNING(-3);

        private final long code;

        ClaimOutcome(long code) {
            this.code = code;
        }

        long code() {
            return code;
        }

        static ClaimOutcome fromCode(Long code) {
            if (code != null) {
                for (ClaimOutcome outcome : values()) {
                    if (outcome.code == code) return outcome;
                }
            }
            throw new IllegalArgumentException("Unknown portable import claim outcome: " + code);
        }
    }

    record Lease(String ownerId, long fencingToken) {
        public Lease {
            if (ownerId == null || ownerId.isBlank() || fencingToken <= 0) {
                throw new IllegalArgumentException("Invalid portable import lease");
            }
        }

        String lockValue() {
            return ownerId + ":" + fencingToken;
        }
    }

    record ClaimResult(ClaimOutcome outcome, Lease lease) {
        static ClaimResult fromCode(String ownerId, Long code) {
            if (code == null) throw new IllegalArgumentException("Missing portable import claim result");
            if (code > 0) return new ClaimResult(ClaimOutcome.CREATED, new Lease(ownerId, code));
            return new ClaimResult(ClaimOutcome.fromCode(code), null);
        }
    }

    record StoredJob(
            String jobId,
            String previewToken,
            long userId,
            JobStage stage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            String message,
            Issue error,
            Instant heartbeatAt
    ) {
        JobResponse response() {
            return new JobResponse(jobId, stage, createdAt, startedAt, finishedAt, message, error);
        }
    }
}
