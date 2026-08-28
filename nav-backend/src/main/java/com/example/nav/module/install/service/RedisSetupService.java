package com.example.nav.module.install.service;

import com.example.nav.common.config.RedisConfigurationDigest;
import com.example.nav.common.config.RedisInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.dto.RedisConfigureDTO;
import com.example.nav.module.install.dto.RedisConnectionDTO;
import com.example.nav.module.install.model.RedisConnectionSpec;
import com.example.nav.module.install.model.RedisTlsMode;
import com.example.nav.module.install.vo.RedisConfigureVO;
import com.example.nav.module.install.vo.RedisTestVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Service
public class RedisSetupService {

    private static final Pattern DNS_HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");
    private static final Pattern IPV6_HOST = Pattern.compile("^[0-9A-Fa-f:.%]+$");
    private static final Pattern PEM_CERTIFICATE_CHAIN = Pattern.compile(
            "(?s)\\A\\s*(?:-----BEGIN CERTIFICATE-----\\s+[A-Za-z0-9+/=\\r\\n]+"
                    + "-----END CERTIFICATE-----\\s*)+\\z");
    private static final AtomicBoolean CONFIGURATION_IN_PROGRESS = new AtomicBoolean();

    private final InstallAccessService accessService;
    private final DatabaseConfigurationStore databaseConfigurationStore;
    private final DatabaseIdentityService databaseIdentityService;
    private final JdbcTemplate jdbcTemplate;
    private final RedisConfigurationStore redisConfigurationStore;
    private final RedisConnectionTicketStore ticketStore;
    private final RedisConnectionVerifier connectionVerifier;
    private final ConfigurableApplicationContext applicationContext;
    private final boolean autoRestart;
    private final boolean allowInsecureSetup;

    public RedisSetupService(
            InstallAccessService accessService,
            DatabaseConfigurationStore databaseConfigurationStore,
            DatabaseIdentityService databaseIdentityService,
            JdbcTemplate jdbcTemplate,
            RedisConfigurationStore redisConfigurationStore,
            RedisConnectionTicketStore ticketStore,
            RedisConnectionVerifier connectionVerifier,
            ConfigurableApplicationContext applicationContext,
            RedisInstallProperties redisProperties,
            com.example.nav.common.config.DatabaseInstallProperties databaseProperties
    ) {
        this.accessService = accessService;
        this.databaseConfigurationStore = databaseConfigurationStore;
        this.databaseIdentityService = databaseIdentityService;
        this.jdbcTemplate = jdbcTemplate;
        this.redisConfigurationStore = redisConfigurationStore;
        this.ticketStore = ticketStore;
        this.connectionVerifier = connectionVerifier;
        this.applicationContext = applicationContext;
        this.autoRestart = redisProperties.isAutoRestart();
        this.allowInsecureSetup = databaseProperties.isAllowInsecureSetup();
    }

    public void requireSecureTransport(HttpServletRequest request) {
        if (allowInsecureSetup) return;
        String forwardedProto = request == null ? null : request.getHeader("X-Forwarded-Proto");
        boolean forwardedHttps = forwardedProto != null
                && "https".equalsIgnoreCase(forwardedProto.split(",", 2)[0].trim());
        if (request == null || (!request.isSecure() && !forwardedHttps)) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "Redis 配置包含敏感凭据，只允许通过 HTTPS 提交");
        }
    }

    public RedisTestVO test(RedisConnectionDTO dto) {
        accessService.requireEnabled();
        String databaseInstanceId = requireConfigurableState();
        if (CONFIGURATION_IN_PROGRESS.get()) {
            throw BusinessException.conflict("Redis 配置任务正在执行");
        }
        RedisConnectionSpec spec = normalize(dto);
        connectionVerifier.verifyReadWrite(spec);
        String digest = digest(spec);
        RedisConnectionTicketStore.IssuedTicket issued = ticketStore.issue(
                spec, digest, databaseInstanceId);
        return new RedisTestVO(true, issued.token(), issued.expiresAt());
    }

    public RedisConfigureVO configure(RedisConfigureDTO dto) {
        accessService.requireEnabled();
        String databaseInstanceId = requireConfigurableState();
        if (dto == null) throw BusinessException.badRequest("Redis 配置参数不能为空");
        if (!CONFIGURATION_IN_PROGRESS.compareAndSet(false, true)) {
            throw BusinessException.conflict("另一个 Redis 配置任务正在执行");
        }
        boolean pendingMarker = false;
        try {
            RedisConnectionTicketStore.Ticket ticket = ticketStore.consume(dto.connectionTicket());
            if (!databaseInstanceId.equals(ticket.databaseInstanceId())) {
                throw BusinessException.conflict("数据库实例在 Redis 测试后发生变化，请重新测试");
            }
            String currentDigest = digest(ticket.spec());
            if (!currentDigest.equals(ticket.configurationDigest())) {
                throw BusinessException.conflict("Redis 配置在连接测试后发生变化，请重新测试");
            }
            requireResolutionUnchanged(ticket.spec());
            connectionVerifier.verifyReadWrite(ticket.spec());
            requireInstallationOpen(databaseInstanceId);
            redisConfigurationStore.verifyWritable();
            redisConfigurationStore.beginConfiguration(databaseInstanceId, currentDigest);
            pendingMarker = true;
            redisConfigurationStore.saveExternal(
                    ticket.spec(), currentDigest, databaseInstanceId);
            if (!databaseIdentityService.refresh()
                    || !databaseInstanceId.equals(databaseConfigurationStore.configuredInstanceId())) {
                throw BusinessException.conflict("数据库实例在 Redis 配置期间发生变化");
            }
            requireInstallationOpen(databaseInstanceId);
            redisConfigurationStore.markConfigured(databaseInstanceId, currentDigest);
            pendingMarker = false;
            ticketStore.advanceGeneration();
            if (autoRestart) scheduleContainerRestart();
            return new RedisConfigureVO(true, autoRestart);
        } catch (RuntimeException exception) {
            if (pendingMarker && !redisConfigurationStore.hasPersistedConnection()) {
                try {
                    redisConfigurationStore.clearPendingConfiguration();
                } catch (RuntimeException ignored) {
                    // An uncleared marker intentionally leaves a fail-closed state.
                }
            }
            throw exception;
        } finally {
            CONFIGURATION_IN_PROGRESS.set(false);
        }
    }

    private String requireConfigurableState() {
        if (databaseConfigurationStore.hasCompletedMarker()) {
            throw BusinessException.conflict("站点已经完成安装，不能更改 Redis 连接");
        }
        if (databaseConfigurationStore.hasInvalidOrPendingArtifact()
                || !databaseConfigurationStore.hasPersistedConnection()
                || !databaseIdentityService.isIdentityRequired()
                || !databaseIdentityService.refresh()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "请先完成并验证数据库配置");
        }
        if (redisConfigurationStore.hasInvalidOrPendingArtifact()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置处于不可用状态");
        }
        if (!redisConfigurationStore.isUnconfiguredSource()) {
            if (redisConfigurationStore.hasPersistedConnection()) {
                throw BusinessException.conflict("Redis 连接已经完成配置");
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 连接由运行环境管理，不能通过安装向导修改");
        }
        String databaseInstanceId = databaseConfigurationStore.configuredInstanceId();
        requireInstallationOpen(databaseInstanceId);
        return databaseInstanceId;
    }

    private void requireInstallationOpen(String databaseInstanceId) {
        final RedisInstallationFacts facts;
        try {
            facts = jdbcTemplate.queryForObject("""
                            SELECT
                                (SELECT install_instance_id::text
                                 FROM public.site_config LIMIT 1) AS install_instance_id,
                                (EXISTS (SELECT 1 FROM public.sys_user)
                                 OR EXISTS (
                                     SELECT 1 FROM public.site_config
                                     WHERE install_completed_at IS NOT NULL
                                 )) AS installed
                            """,
                    (resultSet, rowNumber) -> new RedisInstallationFacts(
                            resultSet.getString("install_instance_id"),
                            resultSet.getBoolean("installed")));
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "安装状态暂不可检查");
        }
        if (facts == null || facts.databaseInstanceId() == null
                || !databaseInstanceId.equals(facts.databaseInstanceId())) {
            throw BusinessException.conflict("数据库实例在 Redis 配置期间发生变化");
        }
        if (facts.installed()) {
            try {
                databaseConfigurationStore.markCompleted(databaseInstanceId);
            } catch (RuntimeException ignored) {
                // Database-side completion remains authoritative even if the local lock cannot be repaired.
            }
            throw BusinessException.conflict("站点已经完成安装，不能更改 Redis 连接");
        }
    }

    private RedisConnectionSpec normalize(RedisConnectionDTO dto) {
        if (dto == null) throw BusinessException.badRequest("Redis 连接参数不能为空");
        String host = validateHost(dto.host());
        List<InetAddress> addresses = resolveSafeExternalAddresses(host);
        if (addresses.size() > 16) {
            throw BusinessException.badRequest("Redis 主机解析结果过多");
        }
        int port = range(dto.port() == null ? 6379 : dto.port(), 1, 65535,
                "Redis 端口必须在 1-65535 之间");
        String username = normalizeUsername(dto.username());
        String password = validatePassword(dto.password());
        int database = range(dto.database() == null ? 0 : dto.database(), 0, 65535,
                "Redis 逻辑库必须在 0-65535 之间");
        int connectTimeoutSeconds = range(
                dto.connectTimeoutSeconds() == null ? 3 : dto.connectTimeoutSeconds(),
                1, 10, "Redis 建连超时必须在 1-10 秒之间");
        int readTimeoutSeconds = range(
                dto.readTimeoutSeconds() == null ? 3 : dto.readTimeoutSeconds(),
                1, 10, "Redis 读写超时必须在 1-10 秒之间");
        RedisTlsMode tlsMode = dto.tlsMode() == null ? RedisTlsMode.SYSTEM : dto.tlsMode();
        String ca = null;
        if (tlsMode == RedisTlsMode.CUSTOM_CA) {
            ca = validateCaCertificate(dto.caCertificatePem());
        } else if (dto.caCertificatePem() != null && !dto.caCertificatePem().isBlank()) {
            throw BusinessException.badRequest("当前 Redis TLS 模式不应提交 CA 证书");
        }
        if (tlsMode == RedisTlsMode.DISABLED) {
            if (!Boolean.TRUE.equals(dto.acknowledgeInsecureTransport())) {
                throw BusinessException.badRequest("关闭 Redis TLS 必须确认明文传输风险");
            }
            if (addresses.stream().anyMatch(address -> !isPrivateAddress(address))) {
                throw BusinessException.badRequest("关闭 Redis TLS 只允许使用受信任私网地址");
            }
        }
        return new RedisConnectionSpec(
                host, port, username, password, database, tlsMode, ca,
                Duration.ofSeconds(connectTimeoutSeconds),
                Duration.ofSeconds(readTimeoutSeconds),
                addresses.stream().map(this::numericAddress).distinct().sorted().toList());
    }

    private String digest(RedisConnectionSpec spec) {
        return RedisConfigurationDigest.digest(
                spec.host(), spec.port(), spec.username(), spec.password(), spec.database(),
                spec.tlsMode().name(), spec.caCertificatePem(),
                spec.connectTimeout().toSeconds(), spec.readTimeout().toSeconds(),
                spec.tlsMode() == RedisTlsMode.DISABLED
                        ? spec.resolvedAddresses() : List.of());
    }

    private void requireResolutionUnchanged(RedisConnectionSpec spec) {
        List<String> current = resolveSafeExternalAddresses(spec.host()).stream()
                .map(this::numericAddress).distinct().sorted().toList();
        if (!current.equals(spec.resolvedAddresses())) {
            throw BusinessException.conflict("Redis 主机解析结果已变化，请重新测试连接");
        }
    }

    private List<InetAddress> resolveSafeExternalAddresses(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw new UnknownHostException("empty resolution");
            for (InetAddress address : addresses) {
                String numeric = numericAddress(address);
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isMulticastAddress()
                        || numeric.equals("100.100.100.200")
                        || numeric.equals("169.254.169.254")
                        || numeric.equals("fd00:ec2:0:0:0:0:0:254")) {
                    throw BusinessException.badRequest(
                            "外部 Redis 主机不能指向本机、链路本地、元数据或组播地址");
                }
            }
            return Arrays.stream(addresses).toList();
        } catch (BusinessException exception) {
            throw exception;
        } catch (UnknownHostException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "外部 Redis 主机无法安全解析");
        }
    }

    private boolean isPrivateAddress(InetAddress address) {
        if (address.isSiteLocalAddress()) return true;
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private String numericAddress(InetAddress address) {
        return address.getHostAddress().toLowerCase(Locale.ROOT);
    }

    private String validateHost(String value) {
        if (value == null) throw BusinessException.badRequest("Redis 主机不能为空");
        String host = value.trim();
        boolean dns = DNS_HOST.matcher(host).matches()
                && !host.startsWith(".") && !host.endsWith(".")
                && !host.startsWith("-") && !host.endsWith("-")
                && !host.contains("..");
        boolean ipv6 = IPV6_HOST.matcher(host).matches() && host.contains(":");
        if (!dns && !ipv6) throw BusinessException.badRequest("Redis 主机格式无效");
        return host;
    }

    private String normalizeUsername(String value) {
        String username = value == null ? "" : value.trim();
        if (username.codePointCount(0, username.length()) > 128
                || username.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) || Character.isWhitespace(codePoint))) {
            throw BusinessException.badRequest("Redis 用户名格式无效");
        }
        return username;
    }

    private String validatePassword(String value) {
        if (value == null || value.isBlank() || value.length() > 1024
                || value.codePoints().anyMatch(codePoint ->
                codePoint == 0 || codePoint == '\r' || codePoint == '\n')) {
            throw BusinessException.badRequest("Redis 密码格式无效");
        }
        return value;
    }

    private String validateCaCertificate(String value) {
        if (value == null || value.isBlank() || value.length() > 65536) {
            throw BusinessException.badRequest("CUSTOM_CA 必须提供不超过 64KiB 的 CA 证书");
        }
        if (value.contains("PRIVATE KEY")
                || value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && codePoint != '\r' && codePoint != '\n' && codePoint != '\t')
                || !PEM_CERTIFICATE_CHAIN.matcher(value).matches()) {
            throw BusinessException.badRequest("Redis CA 证书格式无效");
        }
        try (InputStream input = new ByteArrayInputStream(
                value.getBytes(StandardCharsets.US_ASCII))) {
            if (CertificateFactory.getInstance("X.509").generateCertificates(input).isEmpty()) {
                throw new IllegalArgumentException("empty certificate chain");
            }
        } catch (Exception exception) {
            throw BusinessException.badRequest("Redis CA 证书格式无效");
        }
        return value.endsWith("\n") ? value : value + "\n";
    }

    private int range(int value, int minimum, int maximum, String message) {
        if (value < minimum || value > maximum) throw BusinessException.badRequest(message);
        return value;
    }

    private void scheduleContainerRestart() {
        Thread restart = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            if (applicationContext.isActive()) System.exit(0);
        }, "redis-config-restart");
        restart.setDaemon(false);
        restart.start();
    }

    private record RedisInstallationFacts(String databaseInstanceId, boolean installed) {
    }
}
