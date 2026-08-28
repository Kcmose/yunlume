package com.example.nav.module.install.service;

import com.example.nav.common.config.WebInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.common.security.PasswordPolicy;
import com.example.nav.module.install.dto.InstallCompleteDTO;
import com.example.nav.module.install.model.InstallCommand;
import com.example.nav.module.install.vo.InstallCheckVO;
import com.example.nav.module.install.vo.InstallChecksVO;
import com.example.nav.module.install.vo.InstallCompleteVO;
import com.example.nav.module.install.vo.InstallEnvironmentVO;
import com.example.nav.module.install.vo.InstallStatusVO;
import com.example.nav.module.upload.config.UploadStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class InstallService {

    private static final String WEB_INSTALL_MIGRATION = "20260814_0002_web_install_state.sql";
    private static final String WEB_INSTALL_MIGRATION_CHECKSUM =
            "7347e9e96d3c2347e1067624b786437f3b509e9d7e7614e773c6b1e067596d86";
    private static final String INSTANCE_MIGRATION = "20260815_0003_install_instance_identity.sql";
    private static final String INSTANCE_MIGRATION_CHECKSUM =
            "17df5851046d9a79eb24923b4760f8d0440b15a9a68d1609e2bffe2f1ce280fb";
    private static final SecureRandom REDIS_PROBE_RANDOM = new SecureRandom();

    static final String STATE_REQUIRED = "REQUIRED";
    static final String STATE_COMPLETED = "COMPLETED";
    static final String STATE_DISABLED = "DISABLED";
    static final String STATE_UNKNOWN = "UNKNOWN";
    static final String STATE_DATABASE_REQUIRED = "DATABASE_REQUIRED";
    static final String STATE_REDIS_REQUIRED = "REDIS_REQUIRED";

    private final WebInstallProperties properties;
    private final UploadStorageProperties uploadProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final String cacheType;
    private final PasswordEncoder passwordEncoder;
    private final InstallTransactionService transactionService;
    private final InstallAccessService accessService;
    private final DatabaseConfigurationStore configurationStore;
    private final DatabaseIdentityService databaseIdentityService;
    private final RedisConfigurationStore redisConfigurationStore;
    private final String redisRuntimeInstanceId;

    public InstallService(
            WebInstallProperties properties,
            UploadStorageProperties uploadProperties,
            JdbcTemplate jdbcTemplate,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${spring.cache.type:simple}") String cacheType,
            PasswordEncoder passwordEncoder,
            InstallTransactionService transactionService,
            InstallAccessService accessService,
            DatabaseConfigurationStore configurationStore,
            DatabaseIdentityService databaseIdentityService,
            RedisConfigurationStore redisConfigurationStore,
            @Value("${nav.redis-config.expected-instance-id:}") String redisRuntimeInstanceId
    ) {
        this.properties = properties;
        this.uploadProperties = uploadProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplateProvider = redisTemplateProvider;
        this.cacheType = cacheType;
        this.passwordEncoder = passwordEncoder;
        this.transactionService = transactionService;
        this.accessService = accessService;
        this.configurationStore = configurationStore;
        this.databaseIdentityService = databaseIdentityService;
        this.redisConfigurationStore = redisConfigurationStore;
        this.redisRuntimeInstanceId = normalizeUuid(redisRuntimeInstanceId);
    }

    public InstallStatusVO status() {
        if (configurationStore.hasInvalidOrPendingArtifact()) {
            return new InstallStatusVO(STATE_UNKNOWN, false, properties.isEnabled(), false);
        }
        if (configurationStore.hasCompletedMarker()) {
            return new InstallStatusVO(STATE_COMPLETED, false, properties.isEnabled(), false);
        }
        if (configurationStore.isUnconfiguredSource()) {
            if (!properties.isEnabled()) {
                return new InstallStatusVO(STATE_DISABLED, true, false, false);
            }
            return new InstallStatusVO(STATE_DATABASE_REQUIRED, true, true, false);
        }
        if (configurationStore.hasPersistedConnection() && !databaseIdentityService.isIdentityRequired()) {
            return unavailableStatus();
        }
        if (databaseIdentityService.isRequiredAndUnverified() && !databaseIdentityService.refresh()) {
            return unavailableStatus();
        }
        InstallationFacts facts;
        try {
            facts = loadInstallationFacts();
        } catch (RuntimeException exception) {
            return unavailableStatus();
        }
        if (facts == null) {
            return unavailableStatus();
        }

        if (facts.userCount() > 0 || facts.completedCount() > 0) {
            persistCompletedMarkerIfManaged();
            return new InstallStatusVO(STATE_COMPLETED, false, properties.isEnabled(), true);
        }
        if (redisConfigurationStore.hasInvalidOrPendingArtifact()) {
            return new InstallStatusVO(STATE_UNKNOWN, false, properties.isEnabled(), false);
        }
        if (!properties.isEnabled()) {
            return new InstallStatusVO(STATE_DISABLED, true, false, false);
        }
        if (redisConfigurationStore.isUnconfiguredSource()) {
            return new InstallStatusVO(STATE_REDIS_REQUIRED, true, true, false);
        }
        if (redisConfigurationStore.hasPersistedConnection()
                && !isPersistedRedisRuntimeActive()) {
            return new InstallStatusVO(STATE_UNKNOWN, false, properties.isEnabled(), false);
        }
        return new InstallStatusVO(STATE_REQUIRED, true, true, true);
    }

    public InstallEnvironmentVO check() {
        requireFreshInstallation();
        InstallChecksVO checks = environmentChecks();
        return new InstallEnvironmentVO(allChecksPassed(checks), checks);
    }

    public InstallCompleteVO complete(InstallCompleteDTO dto) {
        requireFreshInstallation();
        InstallChecksVO checks = environmentChecks();
        if (!allChecksPassed(checks)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "部署环境尚未准备完成");
        }
        NormalizedInstallInput input = normalizeAndValidate(dto);
        InstallCompleteVO result = transactionService.complete(new InstallCommand(
                input.siteName(),
                input.siteDescription(),
                input.username(),
                input.nickname(),
                passwordEncoder.encode(input.password()),
                configurationStore.hasPersistedConnection()
                        ? configurationStore.configuredInstanceId()
                        : null
        ));
        persistCompletedMarkerIfManaged();
        return result;
    }

    private void requireFreshInstallation() {
        accessService.requireEnabled();
        if (configurationStore.hasInvalidOrPendingArtifact()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "数据库配置处于不可用状态");
        }
        if (configurationStore.isUnconfiguredSource()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "请先完成数据库配置");
        }
        if (configurationStore.hasPersistedConnection() && !databaseIdentityService.isIdentityRequired()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "数据库配置正在等待服务重启生效");
        }
        if (databaseIdentityService.isIdentityRequired() && !databaseIdentityService.refresh()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "数据库实例身份无法验证");
        }
        InstallationFacts facts;
        try {
            facts = loadInstallationFacts();
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "安装状态暂不可检查");
        }
        if (facts == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "安装状态暂不可检查");
        }
        if (facts.userCount() > 0 || facts.completedCount() > 0) {
            throw BusinessException.conflict("站点已经完成安装，不能再次初始化");
        }
        if (redisConfigurationStore.hasInvalidOrPendingArtifact()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 配置处于不可用状态");
        }
        if (redisConfigurationStore.isUnconfiguredSource()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "请先完成 Redis 配置");
        }
        if (redisConfigurationStore.hasPersistedConnection()
                && !isPersistedRedisRuntimeActive()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置正在等待服务重启生效");
        }
    }

    private InstallChecksVO environmentChecks() {
        InstallCheckVO database = checkDatabase();
        InstallCheckVO schema = database.ok() ? checkSchema() : failed("数据库结构暂不可检查");
        InstallCheckVO siteConfig;
        if (!database.ok() || !schema.ok()) {
            siteConfig = failed("当前无法完成此项检查");
        } else {
            try {
                siteConfig = loadInstallationFacts().siteConfigCount() == 1
                        ? passed("站点配置单例可用")
                        : failed("站点配置必须且只能有一条");
            } catch (RuntimeException exception) {
                siteConfig = failed("站点配置暂不可检查");
            }
        }
        InstallCheckVO upload = checkUploadDirectory();
        InstallCheckVO redis = checkRedis();
        return new InstallChecksVO(database, schema, siteConfig, upload, redis);
    }

    private boolean allChecksPassed(InstallChecksVO checks) {
        return checks.database().ok()
                && checks.schema().ok()
                && checks.siteConfig().ok()
                && checks.upload().ok()
                && checks.redis().ok();
    }

    private InstallStatusVO unavailableStatus() {
        if (configurationStore.hasCompletedMarker()) {
            return new InstallStatusVO(STATE_COMPLETED, false, properties.isEnabled(), false);
        }
        if (configurationStore.isUnconfiguredSource() && properties.isEnabled()) {
            return new InstallStatusVO(STATE_DATABASE_REQUIRED, true, true, false);
        }
        return new InstallStatusVO(STATE_UNKNOWN, false, properties.isEnabled(), false);
    }

    private boolean isPersistedRedisRuntimeActive() {
        if (redisRuntimeInstanceId == null || !configurationStore.hasPersistedConnection()) {
            return false;
        }
        try {
            return redisRuntimeInstanceId.equals(configurationStore.configuredInstanceId());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String normalizeUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private InstallCheckVO checkDatabase() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Integer.valueOf(1).equals(result)
                    ? passed("数据库连接正常")
                    : failed("数据库连接检查失败");
        } catch (RuntimeException exception) {
            return failed("数据库不可用");
        }
    }

    private InstallCheckVO checkSchema() {
        try {
            jdbcTemplate.queryForList("""
                    SELECT filename, checksum, applied_at
                    FROM schema_migration WHERE 1 = 0
                    """);
            jdbcTemplate.queryForList("""
                    SELECT id, username, password, nickname, avatar, role, status,
                           token_version, created_at, updated_at
                    FROM sys_user WHERE 1 = 0
                    """);
            jdbcTemplate.queryForList("""
                    SELECT id, site_name, site_description, publish_url, background_type,
                           background_color, background_image, mobile_background_image,
                           font_color, background_effect, music_enabled, music_url,
                           subscribe_enabled, top_content_enabled, message_text, version,
                           install_completed_at, install_instance_id, created_at, updated_at
                    FROM site_config WHERE 1 = 0
                    """);
            jdbcTemplate.queryForList("""
                    SELECT id, name, icon, sort_order, visible, created_at, updated_at
                    FROM nav_category WHERE 1 = 0
                    """);
            jdbcTemplate.queryForList("""
                    SELECT id, category_id, name, url, icon, description, sort_order,
                           is_recommend, is_external, visible, created_at, updated_at
                    FROM nav_bookmark WHERE 1 = 0
                    """);
            jdbcTemplate.queryForList("""
                    SELECT id, name, icon, search_url, placeholder, is_default,
                           sort_order, visible, created_at, updated_at
                    FROM search_engine WHERE 1 = 0
                    """);
            jdbcTemplate.queryForList("""
                    SELECT id, title, url, position, sort_order, visible, created_at, updated_at
                    FROM custom_link WHERE 1 = 0
                    """);
            Integer migrationCount = jdbcTemplate.queryForObject("""
                            SELECT COUNT(*)
                            FROM schema_migration
                            WHERE filename = ? AND checksum = ?
                            """,
                    Integer.class,
                    WEB_INSTALL_MIGRATION,
                    WEB_INSTALL_MIGRATION_CHECKSUM);
            if (!Integer.valueOf(1).equals(migrationCount)) {
                return failed("数据库安装状态迁移尚未登记或校验失败");
            }
            Integer identityMigrationCount = jdbcTemplate.queryForObject("""
                            SELECT COUNT(*)
                            FROM schema_migration
                            WHERE filename = ? AND checksum = ?
                            """,
                    Integer.class,
                    INSTANCE_MIGRATION,
                    INSTANCE_MIGRATION_CHECKSUM);
            if (!Integer.valueOf(1).equals(identityMigrationCount)) {
                return failed("数据库实例身份迁移尚未登记或校验失败");
            }
            return passed("数据库结构完整");
        } catch (RuntimeException exception) {
            return failed("数据库结构尚未完成升级");
        }
    }

    private InstallationFacts loadInstallationFacts() {
        return jdbcTemplate.queryForObject("""
                        SELECT
                            (SELECT COUNT(*) FROM sys_user) AS user_count,
                            (SELECT COUNT(*) FROM site_config) AS site_config_count,
                            (SELECT COUNT(*) FROM site_config WHERE install_completed_at IS NOT NULL) AS completed_count
                        """,
                (resultSet, rowNumber) -> new InstallationFacts(
                        resultSet.getLong("user_count"),
                        resultSet.getLong("site_config_count"),
                        resultSet.getLong("completed_count")
                ));
    }

    private InstallCheckVO checkUploadDirectory() {
        Path temporary = null;
        try {
            if (uploadProperties.getDirectory() == null || uploadProperties.getDirectory().isBlank()) {
                return failed("上传目录未配置");
            }
            Path uploadRoot = Path.of(uploadProperties.getDirectory()).toAbsolutePath().normalize();
            if (Files.exists(uploadRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(uploadRoot)) {
                return failed("上传目录不可用");
            }
            Files.createDirectories(uploadRoot);
            Path realRoot = uploadRoot.toRealPath();
            if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                return failed("上传目录不可用");
            }
            temporary = Files.createTempFile(realRoot, ".install-check-", ".tmp");
            Files.delete(temporary);
            temporary = null;
            return passed("上传目录可写");
        } catch (IOException | RuntimeException exception) {
            return failed("上传目录不可写");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    log.warn("Failed to remove a temporary web-install storage probe");
                }
            }
        }
    }

    private InstallCheckVO checkRedis() {
        if (!"redis".equalsIgnoreCase(cacheType)) {
            return passed("当前配置无需 Redis");
        }
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate == null) {
                return failed("Redis 不可用");
            }
            byte[] random = new byte[16];
            REDIS_PROBE_RANDOM.nextBytes(random);
            String suffix = HexFormat.of().formatHex(random);
            String key = "nav:install:probe:" + suffix;
            String value = "verify-" + suffix;
            boolean created = false;
            try {
                created = Boolean.TRUE.equals(redisTemplate.opsForValue()
                        .setIfAbsent(key, value, Duration.ofSeconds(60)));
                if (!created
                        || !value.equals(redisTemplate.opsForValue().get(key))
                        || !Boolean.TRUE.equals(redisTemplate.delete(key))) {
                    return failed("Redis 不可读写");
                }
                created = false;
                return passed("Redis 读写正常");
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
        } catch (RuntimeException exception) {
            return failed("Redis 不可用");
        }
    }

    private NormalizedInstallInput normalizeAndValidate(InstallCompleteDTO dto) {
        if (dto == null) {
            throw BusinessException.badRequest("安装参数不能为空");
        }
        String siteName = normalizeRequiredSingleLine(dto.siteName(), "站点名称", 50);
        String siteDescription = normalizeOptionalSingleLine(dto.siteDescription(), "站点简介", 255);
        String username = normalizeRequiredSingleLine(dto.username(), "管理员用户名", 32);
        if (!username.matches("^[A-Za-z][A-Za-z0-9._-]{2,31}$")) {
            throw BusinessException.badRequest(
                    "管理员用户名必须以英文字母开头，且只能包含英文字母、数字、点、下划线和连字符");
        }
        String nickname = normalizeRequiredSingleLine(dto.nickname(), "管理员昵称", 50);
        if (dto.password() == null || dto.confirmPassword() == null) {
            throw BusinessException.badRequest("管理员密码和确认密码不能为空");
        }
        if (!Objects.equals(dto.password(), dto.confirmPassword())) {
            throw BusinessException.badRequest("两次输入的管理员密码不一致");
        }
        PasswordPolicy.findViolation(username, dto.password()).ifPresent(message -> {
            throw BusinessException.badRequest(message);
        });
        return new NormalizedInstallInput(
                siteName, siteDescription, username, nickname, dto.password());
    }

    private String normalizeRequiredSingleLine(String value, String field, int maxCodePoints) {
        if (value == null) {
            throw BusinessException.badRequest(field + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw BusinessException.badRequest(field + "不能为空");
        }
        validateSingleLine(normalized, field, maxCodePoints);
        return normalized;
    }

    private String normalizeOptionalSingleLine(String value, String field, int maxCodePoints) {
        String normalized = value == null ? "" : value.trim();
        validateSingleLine(normalized, field, maxCodePoints);
        return normalized;
    }

    private void validateSingleLine(String value, String field, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            throw BusinessException.badRequest(field + "不能超过 " + maxCodePoints + " 个字符");
        }
        if (value.codePoints().anyMatch(this::isUnsafeTextCodePoint)) {
            throw BusinessException.badRequest(field + "不能包含控制字符或换行");
        }
    }

    private boolean isUnsafeTextCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    private void persistCompletedMarkerIfManaged() {
        if (!configurationStore.shouldMaintainLocalState()) return;
        try {
            String instanceId = jdbcTemplate.queryForObject(
                    "SELECT install_instance_id::text FROM public.site_config LIMIT 1", String.class);
            if (instanceId != null && !instanceId.isBlank()) {
                configurationStore.markCompleted(instanceId);
            }
        } catch (RuntimeException exception) {
            log.warn("Unable to persist the local installation completion lock");
        }
    }

    private InstallCheckVO passed(String message) {
        return new InstallCheckVO(true, message);
    }

    private InstallCheckVO failed(String message) {
        return new InstallCheckVO(false, message);
    }

    private record InstallationFacts(long userCount, long siteConfigCount, long completedCount) {
    }

    private record NormalizedInstallInput(
            String siteName,
            String siteDescription,
            String username,
            String nickname,
            String password
    ) {
    }
}
