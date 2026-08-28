package com.example.nav.module.install.service;

import com.example.nav.common.config.DatabaseInstallProperties;
import com.example.nav.module.install.model.DatabaseConnectionSpec;
import com.example.nav.module.install.model.DatabaseSslMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConfigurationStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void runtimeConfigurationIsAtomicallyPersistedAndClosesUnconfiguredSource() throws Exception {
        DatabaseInstallProperties properties = properties();
        DatabaseConfigurationStore store = new DatabaseConfigurationStore(properties);
        assertTrue(store.isUnconfiguredSource());
        store.verifyWritable();
        store.beginConfiguration();

        store.saveExternal(new DatabaseConnectionSpec(
                        "db.example.com",
                        5432,
                        "navigation",
                        "nav-user",
                        "Database!Secret2026",
                        DatabaseSslMode.REQUIRE,
                        null,
                        java.util.List.of("203.0.113.10")),
                "jdbc:postgresql://db.example.com:5432/navigation?sslmode=require&currentSchema=public",
                "e38440cb-07d9-4fdf-9800-5a4ef185ee61");
        store.markConfigured("e38440cb-07d9-4fdf-9800-5a4ef185ee61");

        assertTrue(store.hasPersistedConnection());
        assertFalse(store.isUnconfiguredSource());
        assertFalse(store.hasInvalidOrPendingArtifact());
        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(properties.getConfigFile()))) {
            saved.load(input);
        }
        assertEquals("EXTERNAL", saved.getProperty("nav.database-config.mode"));
        assertEquals("Database!Secret2026", saved.getProperty("spring.datasource.password"));
        if (Files.getFileStore(Path.of(properties.getConfigFile())).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(Path.of(properties.getConfigFile())));
        }
    }

    @Test
    void legacyEnvironmentNeverBecomesDatabaseRequired() {
        DatabaseInstallProperties properties = properties();
        properties.setSource(DatabaseInstallProperties.Source.LEGACY_ENV);
        DatabaseConfigurationStore store = new DatabaseConfigurationStore(properties);

        assertFalse(store.isUnconfiguredSource());
    }

    @Test
    void pendingMarkerIsFailClosedUntilExplicitlyClearedBeforeRemoteMutation() {
        DatabaseConfigurationStore store = new DatabaseConfigurationStore(properties());
        store.verifyWritable();
        store.beginConfiguration();

        assertTrue(store.hasInvalidOrPendingArtifact());
        assertFalse(store.isUnconfiguredSource());

        store.clearPendingConfiguration();
        assertTrue(store.isUnconfiguredSource());
    }

    private DatabaseInstallProperties properties() {
        DatabaseInstallProperties properties = new DatabaseInstallProperties();
        properties.setSource(DatabaseInstallProperties.Source.UNCONFIGURED);
        properties.setConfigFile(temporaryDirectory.resolve("database.properties").toString());
        properties.setConfiguredMarkerFile(temporaryDirectory.resolve("database.configured").toString());
        properties.setCompletedMarkerFile(temporaryDirectory.resolve("install.completed").toString());
        properties.setCaCertificateFile(temporaryDirectory.resolve("postgresql-ca.pem").toString());
        return properties;
    }
}
