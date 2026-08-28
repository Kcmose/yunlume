package com.example.nav.common.config;

import com.example.nav.module.install.model.RedisConnectionSpec;
import com.example.nav.module.install.model.RedisTlsMode;
import com.example.nav.module.install.service.DatabaseConfigurationStore;
import com.example.nav.module.install.service.RedisConfigurationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistedRedisEnvironmentPostProcessorTest {

    private static final String INSTANCE_ID = "e38440cb-07d9-4fdf-9800-5a4ef185ee61";

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshUnconfiguredSourceInjectsNonRoutableBootstrapPlaceholder() {
        MockEnvironment environment = environment()
                .withProperty("NAV_REDIS_SOURCE", "UNCONFIGURED")
                .withProperty("REDIS_HOST", "");

        new PersistedRedisEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertEquals("redis.invalid", environment.getProperty("spring.data.redis.host"));
        assertEquals("true", environment.getProperty("nav.redis-config.placeholder"));
    }

    @Test
    void legacySourceCannotBypassCompletedOrManagedDatabaseRedisState() throws Exception {
        MockEnvironment completed = environment().withProperty("NAV_REDIS_SOURCE", "LEGACY_ENV");
        Files.writeString(temporaryDirectory.resolve("install.completed"), "completed");
        assertThrows(IllegalStateException.class, () ->
                new PersistedRedisEnvironmentPostProcessor()
                        .postProcessEnvironment(completed, null));

        Files.delete(temporaryDirectory.resolve("install.completed"));
        MockEnvironment managed = environment().withProperty("NAV_REDIS_SOURCE", "LEGACY_ENV");
        assertThrows(IllegalStateException.class, () ->
                new PersistedRedisEnvironmentPostProcessor()
                        .postProcessEnvironment(managed, null));
    }

    @Test
    void committedCustomCaConfigurationLoadsSpringSslBundle() throws Exception {
        String ca = trustedCertificatePem();
        RedisConnectionSpec spec = new RedisConnectionSpec(
                "redis.example.com", 6380, "nav-user", "Redis!Secret2026", 1,
                RedisTlsMode.CUSTOM_CA, ca, Duration.ofSeconds(3), Duration.ofSeconds(4),
                List.of("203.0.113.20"));
        RedisConfigurationStore store = persist(spec, List.of());
        MockEnvironment environment = environment();

        new PersistedRedisEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertEquals("redis.example.com", environment.getProperty("spring.data.redis.host"));
        assertEquals("true", environment.getProperty("spring.data.redis.ssl.enabled"));
        assertEquals("redis", environment.getProperty("spring.data.redis.ssl.bundle"));
        assertEquals(store.caCertificateFile().toUri().toString(),
                environment.getProperty("spring.ssl.bundle.pem.redis.truststore.certificate"));
    }

    @Test
    void plaintextPrivateRedisUsesPinnedNumericAddress() {
        RedisConnectionSpec spec = new RedisConnectionSpec(
                "10.23.45.67", 6379, "", "Redis!Secret2026", 0,
                RedisTlsMode.DISABLED, null, Duration.ofSeconds(3), Duration.ofSeconds(3),
                List.of("10.23.45.67"));
        persist(spec, spec.resolvedAddresses());
        MockEnvironment environment = environment();

        new PersistedRedisEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertEquals("10.23.45.67", environment.getProperty("spring.data.redis.host"));
        assertEquals("false", environment.getProperty("spring.data.redis.ssl.enabled"));
    }

    @Test
    void missingRuntimeFileCompletedInstallAndOversizedFileFailClosed() throws Exception {
        Path completed = temporaryDirectory.resolve("install.completed");
        Files.writeString(completed, "completed");
        MockEnvironment missing = environment().withProperty("NAV_REDIS_SOURCE", "UNCONFIGURED");
        assertThrows(IllegalStateException.class, () ->
                new PersistedRedisEnvironmentPostProcessor()
                        .postProcessEnvironment(missing, null));

        Files.delete(completed);
        Path config = temporaryDirectory.resolve("redis.properties");
        Files.writeString(config, "x".repeat(32 * 1024 + 1));
        assertThrows(IllegalStateException.class, () ->
                new PersistedRedisEnvironmentPostProcessor()
                        .postProcessEnvironment(environment(), null));
    }

    @Test
    void databaseUuidOrCaTamperingFailsClosed() throws Exception {
        String ca = trustedCertificatePem();
        RedisConnectionSpec spec = new RedisConnectionSpec(
                "redis.example.com", 6380, "nav-user", "Redis!Secret2026", 0,
                RedisTlsMode.CUSTOM_CA, ca, Duration.ofSeconds(3), Duration.ofSeconds(3),
                List.of("203.0.113.20"));
        RedisConfigurationStore store = persist(spec, List.of());

        MockEnvironment wrongUuid = environment().withProperty(
                "nav.database-config.expected-instance-id",
                "4b9b020d-95cb-4754-906e-94f66a00a413");
        assertThrows(IllegalStateException.class, () ->
                new PersistedRedisEnvironmentPostProcessor()
                        .postProcessEnvironment(wrongUuid, null));

        Files.writeString(store.caCertificateFile(), ca + "trailing-garbage");
        assertThrows(IllegalStateException.class, () ->
                new PersistedRedisEnvironmentPostProcessor()
                        .postProcessEnvironment(environment(), null));
    }

    private RedisConfigurationStore persist(
            RedisConnectionSpec spec,
            List<String> digestAddresses
    ) {
        RedisInstallProperties properties = properties();
        DatabaseConfigurationStore database = mock(DatabaseConfigurationStore.class);
        when(database.hasPersistedConnection()).thenReturn(true);
        when(database.configuredInstanceId()).thenReturn(INSTANCE_ID);
        RedisConfigurationStore store = new RedisConfigurationStore(properties, database);
        String digest = RedisConfigurationDigest.digest(
                spec.host(), spec.port(), spec.username(), spec.password(), spec.database(),
                spec.tlsMode().name(), spec.caCertificatePem(),
                spec.connectTimeout().toSeconds(), spec.readTimeout().toSeconds(), digestAddresses);
        store.beginConfiguration(INSTANCE_ID, digest);
        store.saveExternal(spec, digest, INSTANCE_ID);
        store.markConfigured(INSTANCE_ID, digest);
        return store;
    }

    private RedisInstallProperties properties() {
        RedisInstallProperties properties = new RedisInstallProperties();
        properties.setSource(RedisInstallProperties.Source.UNCONFIGURED);
        properties.setConfigFile(temporaryDirectory.resolve("redis.properties").toString());
        properties.setConfiguredMarkerFile(
                temporaryDirectory.resolve("redis.configured").toString());
        properties.setCaCertificateFile(temporaryDirectory.resolve("redis-ca.pem").toString());
        return properties;
    }

    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("NAV_REDIS_CONFIG_FILE",
                        temporaryDirectory.resolve("redis.properties").toString())
                .withProperty("NAV_REDIS_CONFIGURED_MARKER_FILE",
                        temporaryDirectory.resolve("redis.configured").toString())
                .withProperty("NAV_REDIS_CA_FILE",
                        temporaryDirectory.resolve("redis-ca.pem").toString())
                .withProperty("NAV_INSTALL_COMPLETED_MARKER_FILE",
                        temporaryDirectory.resolve("install.completed").toString())
                .withProperty("nav.database-config.expected-instance-id", INSTANCE_ID);
    }

    private String trustedCertificatePem() throws Exception {
        Path cacerts = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream input = Files.newInputStream(cacerts)) {
            keyStore.load(input, "changeit".toCharArray());
        }
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            Certificate certificate = keyStore.getCertificate(aliases.nextElement());
            if (certificate != null) {
                String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'})
                        .encodeToString(certificate.getEncoded());
                return "-----BEGIN CERTIFICATE-----\n" + encoded
                        + "\n-----END CERTIFICATE-----\n";
            }
        }
        throw new IllegalStateException("JDK trust store contains no certificate");
    }
}
