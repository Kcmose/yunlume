package com.example.nav.module.install.service;

import com.example.nav.common.config.DatabaseInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.model.DatabaseConnectionSpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

@Component
public class DatabaseConfigurationStore {

    private static final String EXTERNAL_MODE = "EXTERNAL";
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final Path configFile;
    private final Path configuredMarkerFile;
    private final Path completedMarkerFile;
    private final Path caCertificateFile;
    private final DatabaseInstallProperties.Source configuredSource;

    public DatabaseConfigurationStore(DatabaseInstallProperties properties) {
        this.configFile = normalizeConfiguredPath(properties.getConfigFile(), "数据库配置文件");
        this.configuredMarkerFile = normalizeConfiguredPath(
                properties.getConfiguredMarkerFile(), "数据库完成配置标记文件");
        this.completedMarkerFile = normalizeConfiguredPath(
                properties.getCompletedMarkerFile(), "安装完成标记文件");
        this.caCertificateFile = normalizeConfiguredPath(
                properties.getCaCertificateFile(), "数据库 CA 证书文件");
        this.configuredSource = properties.getSource() == null
                ? DatabaseInstallProperties.Source.LEGACY_ENV
                : properties.getSource();
    }

    public boolean hasPersistedConnection() {
        return isRegularNonSymbolicFile(configFile);
    }

    public boolean hasCompletedMarker() {
        return isRegularNonSymbolicFile(completedMarkerFile);
    }

    public boolean hasConfiguredMarker() {
        return isRegularNonSymbolicFile(configuredMarkerFile);
    }

    public String configuredInstanceId() {
        if (hasInvalidOrPendingArtifact() || !hasPersistedConnection() || !hasConfiguredMarker()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "数据库配置实例身份不可用");
        }
        try {
            Properties config = readProperties(configFile);
            Properties marker = readProperties(configuredMarkerFile);
            String configInstanceId = UUID.fromString(
                    config.getProperty("nav.database-config.expected-instance-id")).toString();
            String markerInstanceId = UUID.fromString(marker.getProperty("instance-id")).toString();
            if (!configInstanceId.equals(markerInstanceId)) {
                throw new IllegalStateException("identity mismatch");
            }
            return configInstanceId;
        } catch (RuntimeException exception) {
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "数据库配置实例身份不可用");
        }
    }

    public boolean isUnconfiguredSource() {
        return configuredSource == DatabaseInstallProperties.Source.UNCONFIGURED
                && !hasArtifact(configFile)
                && !hasArtifact(configuredMarkerFile)
                && !hasArtifact(completedMarkerFile)
                && !hasArtifact(caCertificateFile);
    }

    public boolean shouldMaintainLocalState() {
        return configuredSource == DatabaseInstallProperties.Source.UNCONFIGURED
                || hasPersistedConnection()
                || hasConfiguredMarker();
    }

    public boolean hasInvalidOrPendingArtifact() {
        if ((hasArtifact(configFile) && !hasPersistedConnection())
                || (hasArtifact(configuredMarkerFile) && !hasConfiguredMarker())
                || (hasArtifact(completedMarkerFile) && !hasCompletedMarker())) {
            return true;
        }
        if (hasArtifact(caCertificateFile)
                && (!isRegularNonSymbolicFile(caCertificateFile) || !hasPersistedConnection())) {
            return true;
        }
        if (!hasPersistedConnection()) {
            return hasArtifact(configuredMarkerFile) || hasArtifact(completedMarkerFile);
        }
        if (!hasConfiguredMarker()) return true;
        try {
            Properties config = readProperties(configFile);
            Properties marker = readProperties(configuredMarkerFile);
            String mode = config.getProperty("nav.database-config.mode");
            String instanceId = config.getProperty("nav.database-config.expected-instance-id");
            String jdbcUrl = config.getProperty("spring.datasource.url", "");
            if (!"1".equals(config.getProperty("nav.database-config.format"))
                    || !EXTERNAL_MODE.equals(mode)
                    || !"1".equals(marker.getProperty("nav.database-marker.format"))
                    || !"CONFIGURED".equals(marker.getProperty("state"))
                    || !java.util.Objects.equals(mode, marker.getProperty("mode"))
                    || !java.util.Objects.equals(instanceId, marker.getProperty("instance-id"))) {
                return true;
            }
            if (hasArtifact(caCertificateFile) != jdbcUrl.contains("sslrootcert=")) return true;
            UUID.fromString(instanceId);
            if (hasCompletedMarker()) {
                Properties completed = readProperties(completedMarkerFile);
                if (!"1".equals(completed.getProperty("nav.install-completed.format"))
                        || !java.util.Objects.equals(instanceId, completed.getProperty("instance-id"))) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException exception) {
            return true;
        }
    }

    public synchronized void saveExternal(DatabaseConnectionSpec spec, String jdbcUrl, String expectedInstanceId) {
        if (spec == null || jdbcUrl == null || spec.username() == null || spec.password() == null) {
            throw BusinessException.badRequest("数据库连接配置不完整");
        }
        if (spec.sslMode() == null) {
            throw BusinessException.badRequest("数据库 SSL 模式不能为空");
        }
        try {
            if (spec.caCertificatePem() != null) {
                writeTextAtomically(caCertificateFile, spec.caCertificatePem());
            }
            Properties values = new Properties();
            values.setProperty("nav.database-config.format", "1");
            values.setProperty("nav.database-config.mode", EXTERNAL_MODE);
            values.setProperty("nav.database-config.expected-instance-id", expectedInstanceId);
            values.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
            values.setProperty("spring.datasource.url", jdbcUrl);
            values.setProperty("spring.datasource.username", spec.username());
            values.setProperty("spring.datasource.password", spec.password());
            writePropertiesAtomically(configFile, values);
        } catch (RuntimeException exception) {
            if (!Files.exists(configFile, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.deleteIfExists(caCertificateFile);
                } catch (IOException ignored) {
                    // The CA is not an active connection config without the properties file.
                }
            }
            throw exception;
        }
    }

    public synchronized void markCompleted(String instanceId) {
        Properties values = new Properties();
        values.setProperty("nav.install-completed.format", "1");
        values.setProperty("instance-id", instanceId);
        values.setProperty("completed-at", Instant.now().toString());
        writePropertiesAtomically(completedMarkerFile, values);
    }

    /**
     * Creates a durable fail-closed sentinel before the installer is allowed to
     * mutate a remote database. A process crash after this point must be
     * reconciled by an operator instead of silently reopening database choice.
     */
    public synchronized void beginConfiguration() {
        if (hasArtifact(configFile) || hasArtifact(configuredMarkerFile)
                || hasArtifact(completedMarkerFile) || hasArtifact(caCertificateFile)) {
            throw BusinessException.conflict("Database configuration state already exists");
        }
        Properties values = new Properties();
        values.setProperty("nav.database-marker.format", "1");
        values.setProperty("state", "PENDING");
        values.setProperty("mode", EXTERNAL_MODE);
        values.setProperty("attempt-id", UUID.randomUUID().toString());
        values.setProperty("started-at", Instant.now().toString());
        reserveConfigurationMarker();
        writePropertiesAtomically(configuredMarkerFile, values);
    }

    public synchronized void markConfigured(String instanceId) {
        if (!hasPersistedConnection()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Persisted database configuration is missing");
        }
        Properties values = new Properties();
        values.setProperty("nav.database-marker.format", "1");
        values.setProperty("state", "CONFIGURED");
        values.setProperty("mode", EXTERNAL_MODE);
        values.setProperty("instance-id", instanceId);
        values.setProperty("configured-at", Instant.now().toString());
        writePropertiesAtomically(configuredMarkerFile, values);
    }

    public synchronized void clearPendingConfiguration() {
        if (!isRegularNonSymbolicFile(configuredMarkerFile)) return;
        Properties values = readProperties(configuredMarkerFile);
        if (!"PENDING".equals(values.getProperty("state"))) return;
        try {
            Files.delete(configuredMarkerFile);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Pending database configuration marker cannot be cleared");
        }
    }

    public void verifyWritable() {
        Set<Path> parents = new HashSet<>();
        parents.add(verifyWritableParent(configFile));
        parents.add(verifyWritableParent(configuredMarkerFile));
        parents.add(verifyWritableParent(completedMarkerFile));
        parents.add(verifyWritableParent(caCertificateFile));
        for (Path parent : parents) {
            probeWritable(parent);
        }
    }

    Path configFile() {
        return configFile;
    }

    Path completedMarkerFile() {
        return completedMarkerFile;
    }

    public Path caCertificateFile() {
        return caCertificateFile;
    }

    private void writeTextAtomically(Path target, String value) {
        Path parent = verifyWritableParent(target);
        Path temporary = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target))) {
                throw new IOException("invalid target");
            }
            temporary = Files.createTempFile(parent, ".nav-ca-", ".tmp");
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
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "数据库 CA 证书无法安全写入");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort cleanup without logging a credential-adjacent path.
                }
            }
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
            throw BusinessException.conflict("Another database configuration attempt already exists");
        }
    }

    private void writePropertiesAtomically(Path target, Properties values) {
        Path parent = verifyWritableParent(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target))) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "安装配置存储不可用");
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".nav-install-", ".tmp");
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
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "安装配置无法安全写入");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Do not expose the path or credential-bearing temporary file name.
                }
            }
        }
    }

    private Path verifyWritableParent(Path target) {
        Path parent = target.getParent();
        if (parent == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "安装配置目录不可用");
        }
        try {
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(parent)) {
                throw new IOException("symbolic link");
            }
            Path realParent = parent.toRealPath();
            if (!parent.toAbsolutePath().normalize().equals(realParent)
                    || !Files.isDirectory(realParent, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isWritable(realParent)) {
                throw new IOException("not writable");
            }
            setOwnerDirectoryOnly(realParent);
            return realParent;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "安装配置目录不可写");
        }
    }

    private void probeWritable(Path parent) {
        Path probe = null;
        try {
            probe = Files.createTempFile(parent, ".nav-write-probe-", ".tmp");
            setOwnerOnly(probe);
            Files.writeString(probe, "ok");
            setOwnerOnly(probe);
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Installation configuration storage is not durably writable");
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // A leftover non-secret probe still causes the next explicit
                    // writable check to fail if the directory became read-only.
                }
            }
        }
    }

    private void setOwnerDirectoryOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // ACL-based platforms do not expose POSIX mode bits. Individual
            // secret files are still restricted by setOwnerOnly().
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

    private Properties readProperties(Path path) {
        Properties values = new Properties();
        try (var input = Files.newInputStream(path)) {
            values.load(input);
            return values;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Database configuration marker cannot be read");
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

    private boolean isRegularNonSymbolicFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    private boolean hasArtifact(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private Path normalizeConfiguredPath(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + "不能为空");
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(label + "无效", exception);
        }
    }
}
