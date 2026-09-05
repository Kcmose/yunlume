package com.example.nav.module.publicdata;

import com.example.nav.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Database-authoritative generation shared by every public cache. */
@Component
public class PublicDataCacheGenerationStore {

    private final JdbcTemplate jdbcTemplate;

    public PublicDataCacheGenerationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long current() {
        return authoritativeGeneration(false);
    }

    long lockCurrent() {
        return authoritativeGeneration(true);
    }

    @Transactional
    public long advanceTo(long requested) {
        return advanceToWhileLocked(requested);
    }

    long advanceToWhileLocked(long requested) {
        requireCanonical(requested);
        long current = authoritativeGeneration(true);
        if (requested <= current) return current;
        int changed = jdbcTemplate.update("""
                UPDATE site_config
                SET version = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = 1 AND version = ?
                """, requested, current);
        if (changed != 1) {
            throw new IllegalStateException("Public cache generation could not be persisted monotonically");
        }
        return authoritativeGeneration(true);
    }

    /** Must run in the caller's business transaction. */
    @Transactional
    public long advance() {
        long current = authoritativeGeneration(true);
        if (current == Integer.MAX_VALUE) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "公共数据缓存版本无法安全推进");
        }
        int changed = jdbcTemplate.update("""
                UPDATE site_config
                SET version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = 1 AND version = ?
                """, current);
        if (changed != 1) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "公共数据缓存版本无法安全推进");
        }
        return authoritativeGeneration(true);
    }

    private long authoritativeGeneration(boolean lock) {
        List<GenerationRow> generations = jdbcTemplate.query(
                "SELECT id, version FROM site_config ORDER BY id" + (lock ? " FOR UPDATE" : ""),
                (resultSet, rowNumber) -> {
                    long id = resultSet.getLong(1);
                    long value = resultSet.getLong(2);
                    return new GenerationRow(id, resultSet.wasNull() ? null : value);
                });
        if (generations.isEmpty()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "站点配置不存在，请管理员检查数据库初始化或备份恢复状态");
        }
        if (generations.size() != 1 || generations.get(0).id() != 1
                || generations.get(0).version() == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "站点配置必须且只能有 id=1 的一条非空缓存版本记录");
        }
        return requireCanonical(generations.get(0).version());
    }

    private long requireCanonical(long generation) {
        if (generation < 0 || generation > Integer.MAX_VALUE) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "站点配置缓存版本超出 0..2147483647 范围");
        }
        return generation;
    }

    private record GenerationRow(long id, Long version) { }
}
