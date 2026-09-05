package com.example.nav.module.datapackage.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Database authority for import serialization and committed terminal truth. */
@Component
class PortableImportCommitStore {

    private final JdbcTemplate jdbc;

    PortableImportCommitStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lockWriter() {
        Integer guard = jdbc.queryForObject(
                "SELECT id FROM portable_import_guard WHERE id = 1 FOR UPDATE", Integer.class);
        if (guard == null || guard != 1) {
            throw new IllegalStateException("导入数据库互斥行不存在");
        }
    }

    void recordCommitted(
            String jobId,
            String previewToken,
            long userId,
            Instant createdAt,
            Instant startedAt,
            int siteVersion
    ) {
        if (jdbc.update("""
                        INSERT INTO portable_import_operation
                            (job_id, preview_token, user_id, created_at, started_at, site_version)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                jobId, previewToken, userId, Timestamp.from(createdAt), Timestamp.from(startedAt), siteVersion) != 1) {
            throw new IllegalStateException("无法登记导入提交标记");
        }
    }

    Optional<CommittedImport> findByJobId(String jobId) {
        return first(jdbc.query("""
                        SELECT job_id, preview_token, user_id, created_at, started_at, committed_at, site_version
                        FROM portable_import_operation WHERE job_id = ?
                        """, (rs, row) -> map(rs), jobId));
    }

    Optional<CommittedImport> findByPreviewToken(String previewToken) {
        return first(jdbc.query("""
                        SELECT job_id, preview_token, user_id, created_at, started_at, committed_at, site_version
                        FROM portable_import_operation WHERE preview_token = ?
                        """, (rs, row) -> map(rs), previewToken));
    }

    Optional<CommittedImport> findCurrent(long userId) {
        return first(jdbc.query("""
                        SELECT job_id, preview_token, user_id, created_at, started_at, committed_at, site_version
                        FROM portable_import_operation WHERE user_id = ?
                        ORDER BY committed_at DESC, job_id DESC LIMIT 1
                        """, (rs, row) -> map(rs), userId));
    }

    private CommittedImport map(java.sql.ResultSet result) throws java.sql.SQLException {
        return new CommittedImport(
                result.getString("job_id"),
                result.getString("preview_token"),
                result.getLong("user_id"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("started_at").toInstant(),
                result.getTimestamp("committed_at").toInstant(),
                result.getInt("site_version"));
    }

    private Optional<CommittedImport> first(List<CommittedImport> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    record CommittedImport(
            String jobId,
            String previewToken,
            long userId,
            Instant createdAt,
            Instant startedAt,
            Instant committedAt,
            int siteVersion
    ) {
        PortableImportJobStore.StoredJob asCompletedJob() {
            return new PortableImportJobStore.StoredJob(
                    jobId, previewToken, userId,
                    com.example.nav.module.datapackage.model.PortablePackageModels.JobStage.COMPLETED,
                    createdAt, startedAt, committedAt, "导入完成", null, committedAt);
        }
    }
}
