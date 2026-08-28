package com.example.nav.module.install.service;

import com.example.nav.common.config.RedisConfigurationDigest;
import com.example.nav.common.config.RedisInstallProperties;
import com.example.nav.module.install.model.RedisConnectionSpec;
import com.example.nav.module.install.model.RedisTlsMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisConfigurationStoreTest {

    private static final String INSTANCE_ID = "e38440cb-07d9-4fdf-9800-5a4ef185ee61";

    @TempDir
    Path temporaryDirectory;

    @Test
    void committedConfigurationIsOwnerOnlyAndDigestProtected() throws Exception {
        DatabaseConfigurationStore databaseStore = configuredDatabase(INSTANCE_ID);
        RedisInstallProperties properties = properties();
        RedisConfigurationStore store = new RedisConfigurationStore(properties, databaseStore);
        RedisConnectionSpec spec = systemTlsSpec();
        String digest = digest(spec);

        assertTrue(store.isUnconfiguredSource());
        store.verifyWritable();
        store.beginConfiguration(INSTANCE_ID, digest);
        assertTrue(store.hasInvalidOrPendingArtifact());
        store.saveExternal(spec, digest, INSTANCE_ID);
        store.markConfigured(INSTANCE_ID, digest);

        assertTrue(store.hasPersistedConnection());
        assertTrue(store.hasConfiguredMarker());
        assertFalse(store.hasInvalidOrPendingArtifact());
        if (Files.getFileStore(store.configFile()).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(store.configFile()));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(store.configFile().getParent()));
        }

        Properties tampered = new Properties();
        try (InputStream input = Files.newInputStream(store.configFile())) {
            tampered.load(input);
        }
        tampered.setProperty("redis.port", "6380");
        try (OutputStream output = Files.newOutputStream(store.configFile())) {
            tampered.store(output, null);
        }
        assertTrue(store.hasInvalidOrPendingArtifact());
    }

    @Test
    void databaseUuidMismatchAndPartialArtifactsFailClosed() {
        RedisInstallProperties properties = properties();
        DatabaseConfigurationStore databaseStore = configuredDatabase(INSTANCE_ID);
        RedisConfigurationStore store = new RedisConfigurationStore(properties, databaseStore);
        RedisConnectionSpec spec = systemTlsSpec();
        String digest = digest(spec);

        store.beginConfiguration(INSTANCE_ID, digest);
        assertTrue(store.hasInvalidOrPendingArtifact());
        store.clearPendingConfiguration();
        assertTrue(store.isUnconfiguredSource());

        store.beginConfiguration(INSTANCE_ID, digest);
        store.saveExternal(spec, digest, INSTANCE_ID);
        store.markConfigured(INSTANCE_ID, digest);
        when(databaseStore.configuredInstanceId())
                .thenReturn("4b9b020d-95cb-4754-906e-94f66a00a413");
        assertTrue(store.hasInvalidOrPendingArtifact());
    }

    @Test
    void completedDatabaseWithAllRedisArtifactsMissingIsNotFresh() {
        RedisInstallProperties properties = properties();
        DatabaseConfigurationStore databaseStore = configuredDatabase(INSTANCE_ID);
        when(databaseStore.hasCompletedMarker()).thenReturn(true);
        RedisConfigurationStore store = new RedisConfigurationStore(properties, databaseStore);

        assertTrue(store.isUnconfiguredSource());
        assertTrue(store.hasInvalidOrPendingArtifact());
    }

    @Test
    void managedDatabaseCannotSwitchToLegacyRedisAfterArtifactsDisappear() {
        RedisInstallProperties properties = properties();
        properties.setSource(RedisInstallProperties.Source.LEGACY_ENV);
        RedisConfigurationStore store = new RedisConfigurationStore(
                properties, configuredDatabase(INSTANCE_ID));

        assertTrue(store.hasInvalidOrPendingArtifact());
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

    private DatabaseConfigurationStore configuredDatabase(String instanceId) {
        DatabaseConfigurationStore store = mock(DatabaseConfigurationStore.class);
        when(store.hasPersistedConnection()).thenReturn(true);
        when(store.configuredInstanceId()).thenReturn(instanceId);
        return store;
    }

    private RedisConnectionSpec systemTlsSpec() {
        return new RedisConnectionSpec(
                "redis.example.com", 6379, "nav-user", "Redis!Secret2026", 0,
                RedisTlsMode.SYSTEM, null, Duration.ofSeconds(3), Duration.ofSeconds(3),
                List.of("203.0.113.20"));
    }

    private String digest(RedisConnectionSpec spec) {
        return RedisConfigurationDigest.digest(
                spec.host(), spec.port(), spec.username(), spec.password(), spec.database(),
                spec.tlsMode().name(), spec.caCertificatePem(),
                spec.connectTimeout().toSeconds(), spec.readTimeout().toSeconds(), List.of());
    }
}
