package com.example.nav.common.config;

import com.example.nav.module.install.model.RedisTlsMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Loads and validates installer-managed Redis settings before auto-configuration. */
public class PersistedRedisEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "installerPersistedRedis";
    static final String DEFAULT_CONFIG_FILE = "/app/config/redis.properties";
    static final String DEFAULT_CONFIGURED_MARKER = "/app/config/redis.configured";
    static final String DEFAULT_CA_FILE = "/app/config/redis-ca.pem";
    static final String DEFAULT_COMPLETED_MARKER = "/app/config/install.completed";
    private static final long MAX_CONFIG_BYTES = 32L * 1024L;
    private static final long MAX_MARKER_BYTES = 16L * 1024L;
    private static final long MAX_CA_BYTES = 64L * 1024L;
    private static final Pattern DNS_HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");
    private static final Pattern IPV6_HOST = Pattern.compile("^[0-9A-Fa-f:.%]+$");
    private static final Pattern PEM_CERTIFICATE_CHAIN = Pattern.compile(
            "(?s)\\A\\s*(?:-----BEGIN CERTIFICATE-----\\s+[A-Za-z0-9+/=\\r\\n]+"
                    + "-----END CERTIFICATE-----\\s*)+\\z");
    private static final Set<PosixFilePermission> OWNER_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path config = configuredPath(environment, "NAV_REDIS_CONFIG_FILE",
                "nav.redis-install.config-file", DEFAULT_CONFIG_FILE);
        Path marker = configuredPath(environment, "NAV_REDIS_CONFIGURED_MARKER_FILE",
                "nav.redis-install.configured-marker-file", DEFAULT_CONFIGURED_MARKER);
        Path caFile = configuredPath(environment, "NAV_REDIS_CA_FILE",
                "nav.redis-install.ca-certificate-file", DEFAULT_CA_FILE);
        Path completedMarker = configuredPath(environment, "NAV_INSTALL_COMPLETED_MARKER_FILE",
                "nav.database-install.completed-marker-file", DEFAULT_COMPLETED_MARKER);

        boolean hasConfig = Files.exists(config, LinkOption.NOFOLLOW_LINKS);
        boolean hasMarker = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
        boolean hasCa = Files.exists(caFile, LinkOption.NOFOLLOW_LINKS);
        boolean unconfiguredSource = isUnconfiguredSource(environment);
        boolean completedInstallation = Files.exists(completedMarker, LinkOption.NOFOLLOW_LINKS);
        boolean managedDatabase = environment.getProperty(
                "nav.database-config.expected-instance-id") != null;
        if (!hasConfig) {
            if (hasMarker || hasCa) {
                throw new IllegalStateException(
                        "Redis connection state exists but its runtime configuration is missing");
            }
            if (completedInstallation) {
                throw new IllegalStateException(
                        "Completed installation is missing its managed Redis configuration");
            }
            if (managedDatabase && !unconfiguredSource) {
                throw new IllegalStateException(
                        "Managed database cannot fall back to legacy Redis environment settings");
            }
            if (unconfiguredSource) {
                Map<String, Object> placeholder = new LinkedHashMap<>();
                placeholder.put("spring.data.redis.host", "redis.invalid");
                placeholder.put("nav.redis-config.placeholder", true);
                environment.getPropertySources().addFirst(
                        new MapPropertySource(PROPERTY_SOURCE_NAME, placeholder));
            }
            return;
        }

        requireSecureFile(config, "Persisted Redis configuration", MAX_CONFIG_BYTES);
        requireSecureFile(marker, "Persisted Redis marker", MAX_MARKER_BYTES);
        Properties properties = load(config, "Persisted Redis configuration");
        Properties committed = load(marker, "Persisted Redis marker");
        String databaseInstanceId = normalizedUuid(
                environment.getProperty("nav.database-config.expected-instance-id"),
                "Persisted Redis configuration requires a committed database identity");
        String expectedInstanceId = normalizedUuid(
                properties.getProperty("nav.redis-config.expected-instance-id"),
                "Persisted Redis database identity is invalid");
        String markerInstanceId = normalizedUuid(committed.getProperty("instance-id"),
                "Persisted Redis marker database identity is invalid");
        if (!databaseInstanceId.equals(expectedInstanceId)
                || !expectedInstanceId.equals(markerInstanceId)) {
            throw new IllegalStateException(
                    "Persisted Redis and database instance identities do not match");
        }
        if (!"1".equals(properties.getProperty("nav.redis-config.format"))
                || !"EXTERNAL".equals(properties.getProperty("nav.redis-config.mode"))) {
            throw new IllegalStateException("Persisted Redis configuration format is unsupported");
        }
        if (!"1".equals(committed.getProperty("nav.redis-marker.format"))
                || !"CONFIGURED".equals(committed.getProperty("state"))
                || !"EXTERNAL".equals(committed.getProperty("mode"))) {
            throw new IllegalStateException("Persisted Redis configuration is not committed");
        }

        String storedDigest = required(properties, "nav.redis-config.digest");
        if (!storedDigest.matches("^[0-9a-f]{64}$")
                || !storedDigest.equals(committed.getProperty("configuration-digest"))) {
            throw new IllegalStateException("Persisted Redis configuration digest is invalid");
        }
        String host = normalizedHost(required(properties, "redis.host"));
        int port = parseInt(properties, "redis.port", 1, 65535);
        String username = properties.getProperty("redis.username", "");
        if (username.codePointCount(0, username.length()) > 128
                || containsUnsafeEndpointCharacters(username)) {
            throw new IllegalStateException("Persisted Redis username is invalid");
        }
        String password = required(properties, "redis.password");
        if (password.length() > 1024 || password.codePoints().anyMatch(
                codePoint -> codePoint == 0 || codePoint == '\r' || codePoint == '\n')) {
            throw new IllegalStateException("Persisted Redis password is invalid");
        }
        int database = parseInt(properties, "redis.database", 0, 65535);
        int connectTimeoutSeconds = parseInt(
                properties, "redis.connect-timeout-seconds", 1, 10);
        int readTimeoutSeconds = parseInt(properties, "redis.read-timeout-seconds", 1, 10);
        RedisTlsMode tlsMode;
        try {
            tlsMode = RedisTlsMode.valueOf(required(properties, "redis.tls-mode"));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Persisted Redis TLS mode is invalid", exception);
        }

        List<String> resolvedAddresses = persistedResolvedAddresses(properties, tlsMode, host);

        String ca = null;
        if (tlsMode == RedisTlsMode.CUSTOM_CA) {
            requireSecureFile(caFile, "Persisted Redis CA certificate", MAX_CA_BYTES);
            try {
                ca = Files.readString(caFile);
            } catch (IOException exception) {
                throw new IllegalStateException("Persisted Redis CA certificate cannot be read", exception);
            }
            validatePersistedCa(ca);
            if (!RedisConfigurationDigest.textDigest(ca)
                    .equals(properties.getProperty("redis.ca-sha256"))) {
                throw new IllegalStateException("Persisted Redis CA certificate digest is invalid");
            }
        } else if (hasCa || properties.containsKey("redis.ca-sha256")) {
            throw new IllegalStateException("Unexpected persisted Redis CA certificate");
        }

        String calculatedDigest = RedisConfigurationDigest.digest(
                host, port, username, password, database, tlsMode.name(), ca,
                connectTimeoutSeconds, readTimeoutSeconds, resolvedAddresses);
        if (!storedDigest.equals(calculatedDigest)) {
            throw new IllegalStateException("Persisted Redis configuration integrity check failed");
        }

        Map<String, Object> redis = new LinkedHashMap<>();
        String connectionHost = tlsMode == RedisTlsMode.DISABLED
                ? resolvedAddresses.get(0) : host;
        redis.put("spring.data.redis.host", connectionHost);
        redis.put("spring.data.redis.port", port);
        redis.put("spring.data.redis.username", username);
        redis.put("spring.data.redis.password", password);
        redis.put("spring.data.redis.database", database);
        redis.put("spring.data.redis.connect-timeout", connectTimeoutSeconds + "s");
        redis.put("spring.data.redis.timeout", readTimeoutSeconds + "s");
        redis.put("spring.data.redis.ssl.enabled", tlsMode != RedisTlsMode.DISABLED);
        redis.put("nav.external-redis.host", connectionHost);
        redis.put("nav.external-redis.port", port);
        redis.put("nav.external-redis.username", username);
        redis.put("nav.external-redis.password", password);
        redis.put("nav.external-redis.database", database);
        redis.put("nav.external-redis.ssl-enabled", tlsMode != RedisTlsMode.DISABLED);
        redis.put("nav.external-redis.connect-timeout", connectTimeoutSeconds + "s");
        redis.put("nav.external-redis.read-timeout", readTimeoutSeconds + "s");
        redis.put("nav.redis-config.expected-instance-id", expectedInstanceId);
        redis.put("nav.redis-config.persisted", true);
        if (tlsMode == RedisTlsMode.CUSTOM_CA) {
            redis.put("spring.ssl.bundle.pem.redis.truststore.certificate",
                    caFile.toUri().toString());
            redis.put("spring.data.redis.ssl.bundle", "redis");
        }
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, redis));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 21;
    }

    private static boolean isUnconfiguredSource(ConfigurableEnvironment environment) {
        String value = firstNonBlank(environment.getProperty("NAV_REDIS_SOURCE"),
                environment.getProperty("nav.redis-install.source"), "LEGACY_ENV");
        return "UNCONFIGURED".equalsIgnoreCase(value);
    }

    private static String normalizedHost(String value) {
        String normalized = value.trim();
        boolean dns = DNS_HOST.matcher(normalized).matches()
                && !normalized.startsWith(".") && !normalized.endsWith(".")
                && !normalized.startsWith("-") && !normalized.endsWith("-")
                && !normalized.contains("..");
        boolean ipv6 = IPV6_HOST.matcher(normalized).matches() && normalized.contains(":");
        if ((!dns && !ipv6) || containsUnsafeEndpointCharacters(normalized)) {
            throw new IllegalStateException("Persisted Redis host is invalid");
        }
        return normalized;
    }

    private static void validatePersistedCa(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_CA_BYTES
                || value.contains("PRIVATE KEY")
                || value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && codePoint != '\r' && codePoint != '\n' && codePoint != '\t')
                || !PEM_CERTIFICATE_CHAIN.matcher(value).matches()) {
            throw new IllegalStateException("Persisted Redis CA certificate is invalid");
        }
        try (InputStream input = new ByteArrayInputStream(
                value.getBytes(StandardCharsets.US_ASCII))) {
            if (CertificateFactory.getInstance("X.509").generateCertificates(input).isEmpty()) {
                throw new IllegalStateException("Persisted Redis CA certificate is empty");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Persisted Redis CA certificate is invalid", exception);
        }
    }

    private static boolean containsUnsafeEndpointCharacters(String value) {
        return value.codePoints().anyMatch(
                codePoint -> Character.isISOControl(codePoint) || Character.isWhitespace(codePoint));
    }

    private static int parseInt(Properties properties, String key, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(required(properties, key));
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Persisted Redis numeric setting is invalid", exception);
        }
    }

    private static List<String> persistedResolvedAddresses(
            Properties properties,
            RedisTlsMode tlsMode,
            String host
    ) {
        if (tlsMode != RedisTlsMode.DISABLED) {
            if (properties.containsKey("redis.resolved-addresses")) {
                throw new IllegalStateException("Unexpected persisted Redis resolved address");
            }
            return List.of();
        }
        String raw = required(properties, "redis.resolved-addresses");
        List<String> saved = Arrays.stream(raw.split(",", -1))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
        if (saved.isEmpty() || saved.size() > 16 || !raw.equals(String.join(",", saved))) {
            throw new IllegalStateException("Persisted Redis private resolution is invalid");
        }
        List<String> current = resolvePrivateAddresses(host);
        if (!saved.equals(current)) {
            throw new IllegalStateException(
                    "Persisted plaintext Redis host resolution has changed");
        }
        return saved;
    }

    private static List<String> resolvePrivateAddresses(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw new UnknownHostException("empty resolution");
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isMulticastAddress()
                        || !isPrivateAddress(address)) {
                    throw new IllegalStateException(
                            "Persisted plaintext Redis host is no longer private");
                }
            }
            return Arrays.stream(addresses)
                    .map(address -> address.getHostAddress().toLowerCase(Locale.ROOT))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (UnknownHostException exception) {
            throw new IllegalStateException(
                    "Persisted plaintext Redis host cannot be safely resolved", exception);
        }
    }

    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isSiteLocalAddress()) return true;
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Persisted Redis configuration is incomplete");
        }
        return value;
    }

    private static Path configuredPath(
            ConfigurableEnvironment environment,
            String envKey,
            String propertyKey,
            String defaultValue
    ) {
        String value = firstNonBlank(environment.getProperty(envKey),
                environment.getProperty(propertyKey), defaultValue);
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Persisted Redis path is invalid", exception);
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate.trim();
        }
        throw new IllegalStateException("Required Redis setting is missing");
    }

    private static void requireSecureFile(Path path, String label, long maximumBytes) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalStateException(label + " must be a regular non-symbolic file");
        }
        try {
            long size = Files.size(path);
            if (size <= 0 || size > maximumBytes) {
                throw new IllegalStateException(label + " size is invalid");
            }
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
}
