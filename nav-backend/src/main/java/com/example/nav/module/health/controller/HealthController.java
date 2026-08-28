package com.example.nav.module.health.controller;

import com.example.nav.common.result.Result;
import com.example.nav.module.health.vo.HealthVO;
import com.example.nav.module.install.service.InstallService;
import com.example.nav.module.install.service.DatabaseConfigurationStore;
import com.example.nav.module.install.service.DatabaseIdentityService;
import com.example.nav.module.install.service.RedisConfigurationStore;
import com.example.nav.module.install.vo.InstallStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.Duration;
import java.security.SecureRandom;
import java.util.HexFormat;

@RestController
public class HealthController {

    private static final SecureRandom REDIS_PROBE_RANDOM = new SecureRandom();
    private static final long REDIS_PROBE_CACHE_NANOS = Duration.ofSeconds(5).toNanos();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final String cacheType;
    private final InstallService installService;
    private final DatabaseConfigurationStore databaseConfigurationStore;
    private final DatabaseIdentityService databaseIdentityService;
    private final RedisConfigurationStore redisConfigurationStore;
    private final Object redisProbeMonitor = new Object();
    private volatile RedisProbeSnapshot redisProbeSnapshot = new RedisProbeSnapshot(false, 0L);

    public HealthController(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${spring.cache.type:simple}") String cacheType,
            InstallService installService,
            DatabaseConfigurationStore databaseConfigurationStore,
            DatabaseIdentityService databaseIdentityService,
            RedisConfigurationStore redisConfigurationStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplateProvider = redisTemplateProvider;
        this.cacheType = cacheType;
        this.installService = installService;
        this.databaseConfigurationStore = databaseConfigurationStore;
        this.databaseIdentityService = databaseIdentityService;
        this.redisConfigurationStore = redisConfigurationStore;
    }

    @GetMapping("/api/health")
    @Operation(summary = "就绪检查")
    public Result<HealthVO> health() {
        if (databaseConfigurationStore.hasInvalidOrPendingArtifact()) {
            throw new IllegalStateException("Database configuration state is incomplete or invalid");
        }
        if (redisConfigurationStore.hasInvalidOrPendingArtifact()) {
            throw new IllegalStateException("Redis configuration state is incomplete or invalid");
        }
        if (databaseConfigurationStore.hasPersistedConnection()
                && !databaseIdentityService.isIdentityRequired()) {
            throw new IllegalStateException("Database configuration is waiting for restart");
        }
        if (databaseConfigurationStore.isUnconfiguredSource()) {
            InstallStatusVO installStatus = installService.status();
            if (installStatus.installationRequired() && installStatus.webInstallEnabled()) {
                return Result.success(new HealthVO("INSTALLING", "nav-backend", Instant.now()));
            }
            throw new IllegalStateException("Database is unconfigured while web installation is disabled");
        }
        if (redisConfigurationStore.isUnconfiguredSource()) {
            InstallStatusVO installStatus = installService.status();
            if ("REDIS_REQUIRED".equals(installStatus.state())
                    && installStatus.installationRequired()
                    && installStatus.webInstallEnabled()) {
                return Result.success(new HealthVO("INSTALLING", "nav-backend", Instant.now()));
            }
            throw new IllegalStateException(
                    "Managed Redis configuration is missing outside the Redis installation step");
        }
        if (redisConfigurationStore.hasPersistedConnection()) {
            InstallStatusVO installStatus = installService.status();
            if ("UNKNOWN".equals(installStatus.state())) {
                throw new IllegalStateException(
                        "Persisted Redis configuration is not active in this runtime");
            }
        }
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (!databaseIdentityService.ensureVerified()) {
                throw new IllegalStateException(
                        "Database instance identity is unavailable or mismatched");
            }

            if ("redis".equalsIgnoreCase(cacheType)) {
                StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
                if (redisTemplate == null) {
                    throw new IllegalStateException("Redis template is unavailable");
                }
                verifyRedisReadWriteCached(redisTemplate);
            }
        } catch (RuntimeException exception) {
            InstallStatusVO installStatus = installService.status();
            if (installStatus.installationRequired() && installStatus.webInstallEnabled()) {
                return Result.success(new HealthVO("INSTALLING", "nav-backend", Instant.now()));
            }
            throw exception;
        }

        return Result.success(new HealthVO("UP", "nav-backend", Instant.now()));
    }

    private void verifyRedisReadWriteCached(StringRedisTemplate redisTemplate) {
        long now = System.nanoTime();
        RedisProbeSnapshot snapshot = redisProbeSnapshot;
        if (now < snapshot.validUntilNanos()) {
            if (!snapshot.ok()) throw new IllegalStateException("Redis read/write probe failed");
            return;
        }
        synchronized (redisProbeMonitor) {
            now = System.nanoTime();
            snapshot = redisProbeSnapshot;
            if (now < snapshot.validUntilNanos()) {
                if (!snapshot.ok()) throw new IllegalStateException("Redis read/write probe failed");
                return;
            }
            try {
                verifyRedisReadWrite(redisTemplate);
                redisProbeSnapshot = new RedisProbeSnapshot(
                        true, System.nanoTime() + REDIS_PROBE_CACHE_NANOS);
            } catch (RuntimeException exception) {
                redisProbeSnapshot = new RedisProbeSnapshot(
                        false, System.nanoTime() + REDIS_PROBE_CACHE_NANOS);
                throw exception;
            }
        }
    }

    private void verifyRedisReadWrite(StringRedisTemplate redisTemplate) {
        byte[] random = new byte[16];
        REDIS_PROBE_RANDOM.nextBytes(random);
        String suffix = HexFormat.of().formatHex(random);
        String key = "nav:health:probe:" + suffix;
        String value = "verify-" + suffix;
        boolean created = false;
        try {
            created = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(key, value, Duration.ofSeconds(60)));
            if (!created
                    || !value.equals(redisTemplate.opsForValue().get(key))
                    || !Boolean.TRUE.equals(redisTemplate.delete(key))) {
                throw new IllegalStateException("Redis read/write probe failed");
            }
            created = false;
        } finally {
            if (created) {
                try {
                    if (value.equals(redisTemplate.opsForValue().get(key))) {
                        redisTemplate.delete(key);
                    }
                } catch (RuntimeException ignored) {
                    // The short TTL bounds cleanup when Redis becomes unavailable mid-probe.
                }
            }
        }
    }

    private record RedisProbeSnapshot(boolean ok, long validUntilNanos) {
    }
}
