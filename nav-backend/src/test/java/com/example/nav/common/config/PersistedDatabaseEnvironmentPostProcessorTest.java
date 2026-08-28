package com.example.nav.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistedDatabaseEnvironmentPostProcessorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void unsupportedPersistedDatabaseModeFailsClosed() throws Exception {
        Path config = temporaryDirectory.resolve("database.properties");
        Properties saved = new Properties();
        saved.setProperty("nav.database-config.format", "1");
        saved.setProperty("nav.database-config.mode", "LOCAL");
        saved.setProperty("nav.database-config.expected-instance-id",
                "00b61475-8c0d-4d22-a08d-c144e989fc36");
        try (OutputStream output = Files.newOutputStream(config)) {
            saved.store(output, null);
        }
        Path marker = temporaryDirectory.resolve("database.configured");
        Properties committed = new Properties();
        committed.setProperty("nav.database-marker.format", "1");
        committed.setProperty("state", "CONFIGURED");
        committed.setProperty("mode", "LOCAL");
        committed.setProperty("instance-id", "00b61475-8c0d-4d22-a08d-c144e989fc36");
        try (OutputStream output = Files.newOutputStream(marker)) {
            committed.store(output, null);
        }
        if (Files.getFileStore(config).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(temporaryDirectory, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            Files.setPosixFilePermissions(config, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.setPosixFilePermissions(marker, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }

        MockEnvironment environment = new MockEnvironment()
                .withProperty("NAV_DATABASE_CONFIG_FILE", config.toString())
                .withProperty("NAV_DATABASE_CONFIGURED_MARKER_FILE", marker.toString())
                .withProperty("NAV_INSTALL_COMPLETED_MARKER_FILE",
                        temporaryDirectory.resolve("install.completed").toString())
                .withProperty("NAV_DATABASE_CA_FILE",
                        temporaryDirectory.resolve("postgresql-ca.pem").toString());

        assertThrows(IllegalStateException.class, () ->
                new PersistedDatabaseEnvironmentPostProcessor()
                        .postProcessEnvironment(environment, null));
    }

    @Test
    void committedMarkerWithoutRuntimeConfigurationFailsClosed() throws Exception {
        Path marker = temporaryDirectory.resolve("database.configured");
        Files.writeString(marker, "nav.database-marker.format=1\nstate=CONFIGURED\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NAV_DATABASE_CONFIG_FILE",
                        temporaryDirectory.resolve("missing.properties").toString())
                .withProperty("NAV_DATABASE_CONFIGURED_MARKER_FILE", marker.toString())
                .withProperty("NAV_INSTALL_COMPLETED_MARKER_FILE",
                        temporaryDirectory.resolve("install.completed").toString())
                .withProperty("NAV_DATABASE_CA_FILE",
                        temporaryDirectory.resolve("postgresql-ca.pem").toString());

        assertThrows(IllegalStateException.class, () ->
                new PersistedDatabaseEnvironmentPostProcessor()
                        .postProcessEnvironment(environment, null));
    }

    @Test
    void orphanedCaCertificateFailsClosed() throws Exception {
        Path ca = temporaryDirectory.resolve("postgresql-ca.pem");
        Files.writeString(ca, "orphaned");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NAV_DATABASE_CONFIG_FILE",
                        temporaryDirectory.resolve("missing.properties").toString())
                .withProperty("NAV_DATABASE_CONFIGURED_MARKER_FILE",
                        temporaryDirectory.resolve("database.configured").toString())
                .withProperty("NAV_INSTALL_COMPLETED_MARKER_FILE",
                        temporaryDirectory.resolve("install.completed").toString())
                .withProperty("NAV_DATABASE_CA_FILE", ca.toString());

        assertThrows(IllegalStateException.class, () ->
                new PersistedDatabaseEnvironmentPostProcessor()
                        .postProcessEnvironment(environment, null));
    }

    @Test
    void duplicateSecuritySensitiveJdbcParametersFailClosed() throws Exception {
        Path config = temporaryDirectory.resolve("database.properties");
        Path marker = temporaryDirectory.resolve("database.configured");
        String instanceId = "00b61475-8c0d-4d22-a08d-c144e989fc36";
        Properties saved = new Properties();
        saved.setProperty("nav.database-config.format", "1");
        saved.setProperty("nav.database-config.mode", "EXTERNAL");
        saved.setProperty("nav.database-config.expected-instance-id", instanceId);
        saved.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
        saved.setProperty("spring.datasource.username", "nav-user");
        saved.setProperty("spring.datasource.password", "Database!Secret2026");
        saved.setProperty("spring.datasource.url",
                "jdbc:postgresql://db.example.com:5432/navigation?sslmode=require"
                        + "&currentSchema=public&connectTimeout=5&socketTimeout=10"
                        + "&tcpKeepAlive=true&ApplicationName=yunlume-installer&sslmode=disable");
        try (OutputStream output = Files.newOutputStream(config)) {
            saved.store(output, null);
        }
        Files.writeString(marker, "nav.database-marker.format=1\nstate=CONFIGURED\n"
                + "mode=EXTERNAL\ninstance-id=" + instanceId + "\n");
        if (Files.getFileStore(config).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(temporaryDirectory, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            Files.setPosixFilePermissions(config, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.setPosixFilePermissions(marker, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NAV_DATABASE_CONFIG_FILE", config.toString())
                .withProperty("NAV_DATABASE_CONFIGURED_MARKER_FILE", marker.toString())
                .withProperty("NAV_INSTALL_COMPLETED_MARKER_FILE",
                        temporaryDirectory.resolve("install.completed").toString())
                .withProperty("NAV_DATABASE_CA_FILE",
                        temporaryDirectory.resolve("postgresql-ca.pem").toString());

        assertThrows(IllegalStateException.class, () ->
                new PersistedDatabaseEnvironmentPostProcessor()
                        .postProcessEnvironment(environment, null));
    }
}
