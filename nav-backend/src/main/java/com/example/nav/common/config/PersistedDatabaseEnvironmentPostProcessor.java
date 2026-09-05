package com.example.nav.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Loads installer-managed database settings before datasource auto-configuration.
 * Every persisted artifact is treated as a fail-closed state machine: a missing,
 * incomplete, symlinked, or overly-permissive file aborts startup instead of
 * falling back to the image's datasource defaults.
 */
public class PersistedDatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "installerPersistedDatabase";
    static final String DEFAULT_CONFIG_FILE = "/app/config/database.properties";
    static final String DEFAULT_CONFIGURED_MARKER = "/app/config/database.configured";
    static final String DEFAULT_COMPLETED_MARKER = "/app/config/install.completed";
    static final String DEFAULT_CA_FILE = "/app/config/postgresql-ca.pem";
    private static final Set<PosixFilePermission> OWNER_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path config = configuredPath(environment, "NAV_DATABASE_CONFIG_FILE",
                "nav.database-install.config-file", DEFAULT_CONFIG_FILE);
        Path configuredMarker = configuredPath(environment, "NAV_DATABASE_CONFIGURED_MARKER_FILE",
                "nav.database-install.configured-marker-file", DEFAULT_CONFIGURED_MARKER);
        Path completedMarker = configuredPath(environment, "NAV_INSTALL_COMPLETED_MARKER_FILE",
                "nav.database-install.completed-marker-file", DEFAULT_COMPLETED_MARKER);
        Path caFile = configuredPath(environment, "NAV_DATABASE_CA_FILE",
                "nav.database-install.ca-certificate-file", DEFAULT_CA_FILE);

        boolean hasConfig = Files.exists(config, LinkOption.NOFOLLOW_LINKS);
        boolean hasConfiguredMarker = Files.exists(configuredMarker, LinkOption.NOFOLLOW_LINKS);
        boolean hasCompletedMarker = Files.exists(completedMarker, LinkOption.NOFOLLOW_LINKS);
        boolean hasCaFile = Files.exists(caFile, LinkOption.NOFOLLOW_LINKS);
        if (!hasConfig) {
            if (hasConfiguredMarker || hasCompletedMarker || hasCaFile) {
                throw new IllegalStateException(
                        "Database connection state exists but its runtime configuration is missing");
            }
            // An explicitly unconfigured fresh deployment may start the installer.
            // LEGACY_ENV continues to use the normal Spring datasource properties.
            return;
        }

        requireSecureFile(config, "Persisted database configuration");
        requireSecureFile(configuredMarker, "Persisted database marker");
        Properties properties = load(config, "Persisted database configuration");
        Properties marker = load(configuredMarker, "Persisted database marker");
        if (!"1".equals(properties.getProperty("nav.database-config.format"))) {
            throw new IllegalStateException("Persisted database configuration format is unsupported");
        }
        if (!"1".equals(marker.getProperty("nav.database-marker.format"))
                || !"CONFIGURED".equals(marker.getProperty("state"))) {
            throw new IllegalStateException("Persisted database configuration is not committed");
        }

        String expectedInstanceId = normalizedUuid(
                properties.getProperty("nav.database-config.expected-instance-id"),
                "Persisted database instance identity is invalid");
        String markerInstanceId = normalizedUuid(marker.getProperty("instance-id"),
                "Persisted database marker identity is invalid");
        if (!expectedInstanceId.equals(markerInstanceId)) {
            throw new IllegalStateException("Persisted database identity markers do not match");
        }

        Map<String, Object> datasource = new LinkedHashMap<>();
        String mode = properties.getProperty("nav.database-config.mode");
        if (!mode.equals(marker.getProperty("mode"))) {
            throw new IllegalStateException("Persisted database modes do not match");
        }
        if (!"EXTERNAL".equals(mode)) {
            throw new IllegalStateException("Persisted database configuration mode is unsupported");
        }
        copyRequired(properties, datasource, "spring.datasource.url");
        copyRequired(properties, datasource, "spring.datasource.username");
        copyPassword(properties, datasource);
        copyRequired(properties, datasource, "spring.datasource.driver-class-name");
        String url = properties.getProperty("spring.datasource.url");
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("Persisted database URL is not schema-pinned");
        }
        Map<String, String> parameters = parseUniqueQuery(url);
        String sslMode = parameters.get("sslmode");
        boolean requireTls = "require".equals(sslMode);
        boolean verifyCa = "verify-ca".equals(sslMode);
        boolean verifyFull = "verify-full".equals(sslMode);
        if (!(requireTls || verifyCa || verifyFull)) {
            throw new IllegalStateException("Persisted external database TLS mode is unsafe");
        }
        if (!"public".equals(parameters.get("currentSchema"))
                || !"5".equals(parameters.get("connectTimeout"))
                || !"10".equals(parameters.get("socketTimeout"))
                || !"true".equals(parameters.get("tcpKeepAlive"))
                || !"yunlume-installer".equals(parameters.get("ApplicationName"))) {
            throw new IllegalStateException("Persisted database URL parameters are invalid");
        }
        String rootCertificate = parameters.get("sslrootcert");
        Set<String> expectedKeys = rootCertificate == null
                ? Set.of("sslmode", "currentSchema", "connectTimeout", "socketTimeout",
                        "tcpKeepAlive", "ApplicationName")
                : Set.of("sslmode", "currentSchema", "connectTimeout", "socketTimeout",
                        "tcpKeepAlive", "ApplicationName", "sslrootcert");
        if (!parameters.keySet().equals(expectedKeys)) {
            throw new IllegalStateException("Persisted database URL contains unknown parameters");
        }
        if (rootCertificate != null) {
            if (requireTls) {
                throw new IllegalStateException("Unverified TLS mode must not load a CA file");
            }
            requireSecureFile(caFile, "Persisted PostgreSQL CA certificate");
            if (!Path.of(rootCertificate).toAbsolutePath().normalize().equals(caFile)) {
                throw new IllegalStateException(
                        "Persisted PostgreSQL CA certificate path does not match configuration");
            }
        } else if (Files.exists(caFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Unexpected persisted PostgreSQL CA certificate");
        } else if (verifyCa || verifyFull) {
            throw new IllegalStateException("Verified TLS mode is missing its CA certificate");
        }

        if (hasCompletedMarker) {
            requireSecureFile(completedMarker, "Completed installation marker");
            Properties completed = load(completedMarker, "Completed installation marker");
            if (!"1".equals(completed.getProperty("nav.install-completed.format"))) {
                throw new IllegalStateException("Completed installation marker format is unsupported");
            }
            String completedInstanceId = normalizedUuid(completed.getProperty("instance-id"),
                    "Completed installation marker identity is invalid");
            if (!expectedInstanceId.equals(completedInstanceId)) {
                throw new IllegalStateException("Completed installation identity does not match database config");
            }
        }
        datasource.put("nav.database-config.expected-instance-id", expectedInstanceId);
        datasource.put("spring.datasource.hikari.connection-init-sql", identityInitSql(expectedInstanceId));
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, datasource));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private static String identityInitSql(String expectedInstanceId) {
        return "SELECT pg_catalog.set_config('search_path', 'public', false), 1 / CASE WHEN "
                + "(SELECT COUNT(*) = 1 AND COUNT(*) FILTER (WHERE install_instance_id = '"
                + expectedInstanceId + "'::uuid) = 1 FROM public.site_config) THEN 1 ELSE 0 END";
    }

    private static Map<String, String> parseUniqueQuery(String jdbcUrl) {
        int questionMark = jdbcUrl.indexOf('?');
        if (questionMark <= "jdbc:postgresql://".length()
                || questionMark == jdbcUrl.length() - 1
                || jdbcUrl.indexOf('#', questionMark) >= 0) {
            throw new IllegalStateException("Persisted database URL query is invalid");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : jdbcUrl.substring(questionMark + 1).split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator == pair.length() - 1) {
                throw new IllegalStateException("Persisted database URL query is invalid");
            }
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalStateException("Persisted database URL contains duplicate parameters");
            }
        }
        return values;
    }

    private static Path configuredPath(ConfigurableEnvironment environment, String envKey,
                                       String propertyKey, String defaultValue) {
        String value = firstNonBlank(environment.getProperty(envKey),
                environment.getProperty(propertyKey), defaultValue);
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Persisted database path is invalid", exception);
        }
    }

    private static void requireSecureFile(Path path, String label) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalStateException(label + " must be a regular non-symbolic file");
        }
        try {
            Path parent = path.getParent();
            if (parent == null || Files.isSymbolicLink(parent)
                    || !parent.toAbsolutePath().normalize().equals(parent.toRealPath())) {
                throw new IllegalStateException(label + " parent directory is unsafe");
            }
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(OWNER_FILE)) {
                    throw new IllegalStateException(label + " permissions must be 0600");
                }
                if (!Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS)
                        .equals(OWNER_DIRECTORY)) {
                    throw new IllegalStateException(label + " parent permissions must be 0700");
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(label + " cannot be securely inspected", exception);
        }
    }

    private static Properties load(Path path, String label) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException(label + " cannot be read", exception);
        }
    }

    private static String normalizedUuid(String value, String message) {
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private static void copyRequired(Properties source, Map<String, Object> target, String key) {
        String value = source.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Persisted database configuration is incomplete");
        }
        target.put(key, value);
    }

    private static void copyPassword(Properties source, Map<String, Object> target) {
        String value = source.getProperty("spring.datasource.password");
        // 与安装入口一致：密码是原始凭据，非空的空白字符也必须原样保留。
        if (value == null || value.isEmpty() || value.length() > 1024
                || value.codePoints().anyMatch(codePoint -> codePoint == 0
                || codePoint == '\r' || codePoint == '\n')) {
            throw new IllegalStateException("Persisted database password is invalid");
        }
        target.put("spring.datasource.password", value);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate.trim();
        }
        throw new IllegalStateException("Required database path is missing");
    }

}
