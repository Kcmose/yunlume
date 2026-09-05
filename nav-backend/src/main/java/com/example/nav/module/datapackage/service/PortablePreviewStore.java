package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** 有界预检存储；归档与元数据共同归属于随机 token，不能用节点本地路径交换预检。 */
interface PortablePreviewStore {
    int MAX_PREVIEWS = 8;
    long MAX_RESERVED_BYTES = 512L * 1024 * 1024;
    int CHUNK_BYTES = 1024 * 1024;
    int MAX_CHUNKS = (int) (PortablePackageModels.MAX_ARCHIVE_BYTES / CHUNK_BYTES);
    Duration PREVIEW_TTL = Duration.ofMinutes(15);
    Duration ACTIVE_TTL = Duration.ofHours(24);

    Entry reserve(String token, long userId, long archiveBytes, Instant expiresAt);

    Entry publish(Entry reservation, String archiveSha256, String businessRevision, Path archive, Runnable releaseWorkspace);

    default Entry publish(Entry reservation, String archiveSha256, String businessRevision, Path archive) {
        return publish(reservation, archiveSha256, businessRevision, archive, () -> {});
    }

    Optional<Entry> find(String token, long userId);

    Entry activate(Entry preview, String jobId);

    void copyArchive(Entry preview, Path target);

    void renew(Entry preview);

    void renewProcessing(Entry reservation);

    void release(Entry preview);

    void cleanupExpired();

    static long reservationBytes(long archiveBytes) {
        if (archiveBytes <= 0 || archiveBytes > PortablePackageModels.MAX_ARCHIVE_BYTES) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "ZIP 上传文件不能超过 64MiB");
        }
        // 同时计入共享归档、本地工作副本及最多64MiB的解压空间。
        return 2 * archiveBytes + PortablePackageModels.MAX_EXPANDED_BYTES;
    }

    static BusinessException full() {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "导入预检容量已满，请等待已有预检过期或任务完成后重试");
    }

    static BusinessException missing() {
        return BusinessException.notFound("导入预检不存在或已过期");
    }

    record Entry(
            int slot, String token, @JsonFormat(shape = JsonFormat.Shape.STRING) long userId, long archiveBytes,
            String archiveSha256, String businessRevision, long expiresAtMillis,
            boolean ready, String activeJobId, long retainUntilMillis
    ) {
        Entry published(String sha256, String revision, Instant now) {
            long deadline = now.plus(PREVIEW_TTL).toEpochMilli();
            return new Entry(slot, token, userId, archiveBytes, sha256, revision,
                    deadline, true, null, deadline);
        }

        Entry active(String jobId, Instant now) {
            return new Entry(slot, token, userId, archiveBytes, archiveSha256, businessRevision,
                    expiresAtMillis, ready, jobId, now.plus(ACTIVE_TTL).toEpochMilli());
        }

        Entry processing(Instant now) {
            return new Entry(slot, token, userId, archiveBytes, archiveSha256, businessRevision,
                    expiresAtMillis, ready, activeJobId, now.plus(ACTIVE_TTL).toEpochMilli());
        }

        Instant expiresAt() { return Instant.ofEpochMilli(expiresAtMillis); }
        long reservedBytes() { return reservationBytes(archiveBytes); }
        int chunks() { return Math.toIntExact((archiveBytes + CHUNK_BYTES - 1) / CHUNK_BYTES); }
    }
}
