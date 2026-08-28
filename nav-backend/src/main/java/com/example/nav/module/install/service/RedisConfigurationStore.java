package com.example.nav.module.install.service;

import com.example.nav.common.config.RedisConfigurationDigest;
import com.example.nav.common.config.RedisInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.model.RedisConnectionSpec;
import com.example.nav.module.install.model.RedisTlsMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

@Component
public class RedisConfigurationStore {

    static final String EXTERNAL_MODE = "EXTERNAL";
    static final String FORMAT = "1";
    private static final long MAX_CONFIG_BYTES = 32L * 1024L;
    private static final long MAX_MARKER_BYTES = 16L * 1024L;
    private static final long MAX_CA_BYTES = 64L * 1024L;
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );

    private final Path configFile;
    private final Path configuredMarkerFile;
    private final Path caCertificateFile;
    private final RedisInstallProperties.Source configuredSource;
    private final DatabaseConfigurationStore databaseConfigurationStore;

    public RedisConfigurationStore(
            RedisInstallProperties properties,
            DatabaseConfigurationStore databaseConfigurationStore
    ) {
        this.configFile = normalizeConfiguredPath(properties.getConfigFile(), "Redis 配置文件");
        this.configuredMarkerFile = normalizeConfiguredPath(
                properties.getConfiguredMarkerFile(), "Redis 配置标记文件");
        this.caCertificateFile = normalizeConfiguredPath(
                properties.getCaCertificateFile(), "Redis CA 证书文件");
        this.configuredSource = properties.getSource() == null
                ? RedisInstallProperties.Source.LEGACY_ENV
                : properties.getSource();
        this.databaseConfigurationStore = databaseConfigurationStore;
    }

    public boolean hasPersistedConnection() {
        return isSecureRegularFile(configFile, MAX_CONFIG_BYTES);
    }

    public boolean hasConfiguredMarker() {
        return isSecureRegularFile(configuredMarkerFile, MAX_MARKER_BYTES);
    }

    public boolean isUnconfiguredSource() {
        return configuredSource == RedisInstallProperties.Source.UNCONFIGURED
                && !hasArtifact(configFile)
                && !hasArtifact(configuredMarkerFile)
                && !hasArtifact(caCertificateFile);
    }

    public boolean isLegacyEnvironmentSource() {
        return configuredSource == RedisInstallProperties.Source.LEGACY_ENV
                && !hasArtifact(configFile)
                && !hasArtifact(configuredMarkerFile)
                && !hasArtifact(caCertificateFile);
    }

    public boolean hasInvalidOrPendingArtifact() {
        boolean hasConfigArtifact = hasArtifact(configFile);
        boolean hasMarkerArtifact = hasArtifact(configuredMarkerFile);
        boolean hasCaArtifact = hasArtifact(caCertificateFile);
        if (!hasConfigArtifact) {
            return hasMarkerArtifact || hasCaArtifact
                    || databaseConfigurationStore.hasCompletedMarker()
                    || (configuredSource == RedisInstallProperties.Source.LEGACY_ENV
                    && databaseConfigurationStore.hasPersistedConnection());
        }
        if (!hasPersistedConnection() || !hasMarkerArtifact || !hasConfiguredMarker()) {
            return true;
        }
        if (hasCaArtifact && !isSecureRegularFile(caCertificateFile, MAX_CA_BYTES)) {
            return true;
        }
        if (databaseConfigurationStore.hasInvalidOrPendingArtifact()
                || !databaseConfigurationStore.hasPersistedConnection()) {
            return true;
        }
        try {
            Properties config = readProperties(configFile);
            Properties marker = readProperties(configuredMarkerFile);
            String expectedInstanceId = normalizedUuid(
                    config.getProperty("nav.redis-config.expected-instance-id"));
            if (!expectedInstanceId.equals(databaseConfigurationStore.configuredInstanceId())) {
                return true;
            }
            if (!FORMAT.equals(config.getProperty("nav.redis-config.format"))
                    || !EXTERNAL_MODE.equals(config.getProperty("nav.redis-config.mode"))
                    || !FORMAT.equals(marker.getProperty("nav.redis-marker.format"))
                    || !"CONFIGURED".equals(marker.getProperty("state"))
                    || !EXTERNAL_MODE.equals(marker.getProperty("mode"))
                    || !expectedInstanceId.equals(normalizedUuid(marker.getProperty("instance-id")))) {
                return true;
            }
            String storedDigest = required(config, "nav.redis-config.digest");
            if (!storedDigest.matches("^[0-9a-f]{64}$")
                    || !storedDigest.equals(marker.getProperty("configuration-digest"))) {
                return true;
            }
            RedisTlsMode tlsMode = RedisTlsMode.valueOf(required(config, "redis.tls-mode"));
            List<String> resolvedAddresses = persistedResolvedAddresses(config, tlsMode);
            String ca = hasCaArtifact ? Files.readString(caCertificateFile) : null;
            if ((tlsMode == RedisTlsMode.CUSTOM_CA) != hasCaArtifact) {
                return true;
            }
            if (tlsMode == RedisTlsMode.CUSTOM_CA
                    && !RedisConfigurationDigest.textDigest(ca)
                    .equals(config.getProperty("redis.ca-sha256"))) {
                return true;
            }
            if (tlsMode != RedisTlsMode.CUSTOM_CA && config.containsKey("redis.ca-sha256")) {
                return true;
            }
            String calculated = RedisConfigurationDigest.digest(
                    required(config, "redis.host"),
                    parseInt(config, "redis.port", 1, 65535),
                    config.getProperty("redis.username", ""),
                    required(config, "redis.password"),
                    parseInt(config, "redis.database", 0, 65535),
                    tlsMode.name(),
                    ca,
                    parseInt(config, "redis.connect-timeout-seconds", 1, 10),
                    parseInt(config, "redis.read-timeout-seconds", 1, 10),
                    resolvedAddresses);
            return !storedDigest.equals(calculated);
        } catch (IOException | RuntimeException exception) {
            return true;
        }
    }

    public synchronized void beginConfiguration(String instanceId, String configurationDigest) {
        requireNoArtifacts();
        Properties values = new Properties();
        values.setProperty("nav.redis-marker.format", FORMAT);
        values.setProperty("state", "PENDING");
        values.setProperty("mode", EXTERNAL_MODE);
        values.setProperty("instance-id", normalizedUuid(instanceId));
        values.setProperty("configuration-digest", requireDigest(configurationDigest));
        values.setProperty("attempt-id", UUID.randomUUID().toString());
        values.setProperty("started-at", Instant.now().toString());
        reserveConfigurationMarker();
        writePropertiesAtomically(configuredMarkerFile, values);
    }

    public synchronized void saveExternal(
            RedisConnectionSpec spec,
            String configurationDigest,
            String instanceId
    ) {
        if (spec == null || spec.tlsMode() == null || spec.password() == null) {
            throw BusinessException.badRequest("Redis 连接配置不完整");
        }
        String normalizedInstanceId = normalizedUuid(instanceId);
        String normalizedDigest = requireDigest(configurationDigest);
        try {
            if (spec.tlsMode() == RedisTlsMode.CUSTOM_CA) {
                writeTextAtomically(caCertificateFile, spec.caCertificatePem());
            }
            Properties values = new Properties();
            values.setProperty("nav.redis-config.format", FORMAT);
            values.setProperty("nav.redis-config.mode", EXTERNAL_MODE);
            values.setProperty("nav.redis-config.expected-instance-id", normalizedInstanceId);
            values.setProperty("nav.redis-config.digest", normalizedDigest);
            values.setProperty("redis.host", spec.host());
            values.setProperty("redis.port", Integer.toString(spec.port()));
            values.setProperty("redis.username", spec.username());
            values.setProperty("redis.password", spec.password());
            values.setProperty("redis.database", Integer.toString(spec.database()));
            values.setProperty("redis.tls-mode", spec.tlsMode().name());
            values.setProperty("redis.connect-timeout-seconds",
                    Long.toString(spec.connectTimeout().toSeconds()));
            values.setProperty("redis.read-timeout-seconds",
                    Long.toString(spec.readTimeout().toSeconds()));
            if (spec.tlsMode() == RedisTlsMode.DISABLED) {
                if (spec.resolvedAddresses().isEmpty()) {
                    throw BusinessException.badRequest("Redis 私网解析结果不能为空");
                }
                values.setProperty("redis.resolved-addresses",
                        String.join(",", spec.resolvedAddresses()));
            }
            if (spec.tlsMode() == RedisTlsMode.CUSTOM_CA) {
                values.setProperty("redis.ca-sha256",
                        RedisConfigurationDigest.textDigest(spec.caCertificatePem()));
            }
            writePropertiesAtomically(configFile, values);
        } catch (RuntimeException exception) {
            if (!Files.exists(configFile, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.deleteIfExists(caCertificateFile);
                } catch (IOException ignored) {
                    // A CA without a committed runtime file is never activated.
                }
            }
            throw exception;
        }
    }

    public synchronized void markConfigured(String instanceId, String configurationDigest) {
        if (!hasPersistedConnection()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 运行时配置缺失");
        }
        Properties values = new Properties();
        values.setProperty("nav.redis-marker.format", FORMAT);
        values.setProperty("state", "CONFIGURED");
        values.setProperty("mode", EXTERNAL_MODE);
        values.setProperty("instance-id", normalizedUuid(instanceId));
        values.setProperty("configuration-digest", requireDigest(configurationDigest));
        values.setProperty("configured-at", Instant.now().toString());
        writePropertiesAtomically(configuredMarkerFile, values);
    }

    public synchronized void clearPendingConfiguration() {
        if (!isSecureRegularFile(configuredMarkerFile, MAX_MARKER_BYTES)) return;
        Properties marker = readProperties(configuredMarkerFile);
        if (!"PENDING".equals(marker.getProperty("state"))) return;
        try {
            Files.delete(configuredMarkerFile);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置暂存标记无法清除");
        }
    }

    public void verifyWritable() {
        Set<Path> parents = new HashSet<>();
        parents.add(verifyWritableParent(configFile));
        parents.add(verifyWritableParent(configuredMarkerFile));
        parents.add(verifyWritableParent(caCertificateFile));
        for (Path parent : parents) {
            probeWritable(parent);
        }
    }

    Path configFile() {
        return configFile;
    }

    Path configuredMarkerFile() {
        return configuredMarkerFile;
    }

    public Path caCertificateFile() {
        return caCertificateFile;
    }

    private void requireNoArtifacts() {
        if (hasArtifact(configFile) || hasArtifact(configuredMarkerFile)
                || hasArtifact(caCertificateFile)) {
            throw BusinessException.conflict("Redis 配置状态已存在");
        }
    }

    private void reserveConfigurationMarker() {
        Path parent = verifyWritableParent(configuredMarkerFile);
        try {
            Files.createFile(configuredMarkerFile);
            setOwnerOnly(configuredMarkerFile);
            forceFile(configuredMarkerFile);
            forceDirectory(parent);
        } catch (IOException | RuntimeException exception) {
            throw BusinessException.conflict("另一个 Redis 配置任务已经存在");
        }
    }

    private void writeTextAtomically(Path target, String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest("Redis CA 证书不能为空");
        }
        Path parent = verifyWritableParent(target);
        Path temporary = null;
        try {
            if (hasArtifact(target)
                    && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target))) {
                throw new IOException("invalid target");
            }
            temporary = Files.createTempFile(parent, ".nav-redis-ca-", ".tmp");
            setOwnerOnly(temporary);
            Files.writeString(temporary, value);
            setOwnerOnly(temporary);
            forceFile(temporary);
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            setOwnerOnly(target);
            forceFile(target);
            forceDirectory(parent);
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis CA 证书无法安全写入");
        } finally {
            deleteTemporary(temporary);
        }
    }

    private void writePropertiesAtomically(Path target, Properties values) {
        Path parent = verifyWritableParent(target);
        if (hasArtifact(target)
                && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target))) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置存储不可用");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".nav-redis-install-", ".tmp");
            setOwnerOnly(temporary);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, null);
            }
            setOwnerOnly(temporary);
            forceFile(temporary);
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            setOwnerOnly(target);
            forceFile(target);
            forceDirectory(parent);
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置无法安全写入");
        } finally {
            deleteTemporary(temporary);
        }
    }

    private Path verifyWritableParent(Path target) {
        Path parent = target.getParent();
        if (parent == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置目录不可用");
        }
        try {
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(parent)) throw new IOException("symbolic link");
            Path realParent = parent.toRealPath();
            if (!parent.toAbsolutePath().normalize().equals(realParent)
                    || !Files.isDirectory(realParent, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isWritable(realParent)) {
                throw new IOException("not writable");
            }
            setOwnerDirectoryOnly(realParent);
            return realParent;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置目录不可写");
        }
    }

    private void probeWritable(Path parent) {
        Path probe = null;
        try {
            probe = Files.createTempFile(parent, ".nav-redis-write-probe-", ".tmp");
            setOwnerOnly(probe);
            Files.writeString(probe, "ok");
            setOwnerOnly(probe);
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置存储不可持久写入");
        } finally {
            deleteTemporary(probe);
        }
    }

    private boolean isSecureRegularFile(Path path, long maximumBytes) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return false;
        }
        try {
            long size = Files.size(path);
            if (size <= 0 || size > maximumBytes) return false;
            Path parent = path.getParent();
            if (parent == null || Files.isSymbolicLink(parent)
                    || !parent.toAbsolutePath().normalize().equals(parent.toRealPath())) {
                return false;
            }
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(OWNER_ONLY)
                        && Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS)
                        .equals(OWNER_DIRECTORY_ONLY);
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private Properties readProperties(Path path) {
        Properties values = new Properties();
        try (var input = Files.newInputStream(path)) {
            values.load(input);
            return values;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置状态无法读取");
        }
    }

    private String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing value");
        return value;
    }

    private int parseInt(Properties values, String key, int minimum, int maximum) {
        int value = Integer.parseInt(required(values, key));
        if (value < minimum || value > maximum) throw new IllegalStateException("invalid range");
        return value;
    }

    private List<String> persistedResolvedAddresses(
            Properties values,
            RedisTlsMode tlsMode
    ) throws IOException {
        if (tlsMode != RedisTlsMode.DISABLED) {
            if (values.containsKey("redis.resolved-addresses")) {
                throw new IllegalStateException("unexpected resolved address");
            }
            return List.of();
        }
        String raw = required(values, "redis.resolved-addresses");
        List<String> addresses = Arrays.stream(raw.split(",", -1))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
        if (addresses.isEmpty() || addresses.size() > 16
                || !raw.equals(String.join(",", addresses))) {
            throw new IllegalStateException("invalid resolved addresses");
        }
        for (String addressValue : addresses) {
            InetAddress address = InetAddress.getByName(addressValue);
            if (!address.getHostAddress().toLowerCase(Locale.ROOT).equals(addressValue)
                    || address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isMulticastAddress()
                    || !isPrivateAddress(address)) {
                throw new IllegalStateException("invalid private address");
            }
        }
        return addresses;
    }

    private boolean isPrivateAddress(InetAddress address) {
        if (address.isSiteLocalAddress()) return true;
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private String requireDigest(String value) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置摘要无效");
        }
        return value;
    }

    private String normalizedUuid(String value) {
        try {
            return UUID.fromString(Objects.requireNonNull(value)).toString();
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis 配置数据库实例身份无效");
        }
    }

    private void setOwnerDirectoryOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_DIRECTORY_ONLY);
        } catch (UnsupportedOperationException ignored) {
            // ACL-based filesystems are handled by the per-file owner restriction.
        }
    }

    private void setOwnerOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException exception) {
            if (!path.toFile().setReadable(false, false)
                    || !path.toFile().setWritable(false, false)
                    || !path.toFile().setReadable(true, true)
                    || !path.toFile().setWritable(true, true)) {
                throw new IOException("owner-only permissions unavailable", exception);
            }
        }
    }

    private void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectory(Path directory) throws IOException {
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private void deleteTemporary(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Never log credential-adjacent temporary paths.
        }
    }

    private boolean hasArtifact(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private Path normalizeConfiguredPath(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalStateException(label + "不能为空");
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(label + "无效", exception);
        }
    }
}
