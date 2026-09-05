package com.example.nav.common.config;

import com.example.nav.module.install.model.DatabaseConnectionSpec;
import com.example.nav.module.install.model.DatabaseSslMode;
import com.example.nav.module.install.service.DatabaseConfigurationStore;
import com.example.nav.module.install.service.DatabaseSetupService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistedDatabaseEnvironmentPostProcessorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void installerPasswordSurvivesPersistAndReloadWithoutTrimming() throws Exception {
        for (String password : List.of("   ", "\t", "  surrounding spaces  ", "normal-password")) {
            DatabaseInstallProperties paths = new DatabaseInstallProperties();
            Path directory = Files.createDirectory(temporaryDirectory.resolve("config-" + password.hashCode()));
            paths.setConfigFile(directory.resolve("database.properties").toString());
            paths.setConfiguredMarkerFile(directory.resolve("database.configured").toString());
            paths.setCompletedMarkerFile(directory.resolve("install.completed").toString());
            paths.setCaCertificateFile(directory.resolve("postgresql-ca.pem").toString());
            DatabaseSetupService service = new DatabaseSetupService(null, null, null, null, null, paths);
            var validate = DatabaseSetupService.class.getDeclaredMethod("validatePassword", String.class);
            validate.setAccessible(true);
            String accepted = (String) validate.invoke(service, password);
            var spec = new DatabaseConnectionSpec("db.example.com", 5432, "navigation", "nav_user",
                    accepted, DatabaseSslMode.REQUIRE, null, List.of("192.0.2.10"));
            String identity = "00b61475-8c0d-4d22-a08d-c144e989fc36";
            var store = new DatabaseConfigurationStore(paths);
            store.saveExternal(spec, "jdbc:postgresql://db.example.com:5432/navigation?sslmode=require"
                    + "&currentSchema=public&connectTimeout=5&socketTimeout=10&tcpKeepAlive=true"
                    + "&ApplicationName=yunlume-installer", identity);
            store.markConfigured(identity);
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("NAV_DATABASE_CONFIG_FILE", paths.getConfigFile())
                    .withProperty("NAV_DATABASE_CONFIGURED_MARKER_FILE", paths.getConfiguredMarkerFile())
                    .withProperty("NAV_INSTALL_COMPLETED_MARKER_FILE", paths.getCompletedMarkerFile())
                    .withProperty("NAV_DATABASE_CA_FILE", paths.getCaCertificateFile());
            new PersistedDatabaseEnvironmentPostProcessor().postProcessEnvironment(environment, null);
            assertEquals(password, environment.getProperty("spring.datasource.password"));
        }
    }

    @Test
    void persistedPasswordRejectsSameInvalidValuesAsInstaller() throws Exception {
        DatabaseSetupService service = new DatabaseSetupService(
                null, null, null, null, null, new DatabaseInstallProperties());
        var validate = DatabaseSetupService.class.getDeclaredMethod("validatePassword", String.class);
        validate.setAccessible(true);
        var copy = PersistedDatabaseEnvironmentPostProcessor.class.getDeclaredMethod(
                "copyPassword", Properties.class, java.util.Map.class);
        copy.setAccessible(true);
        for (String password : new String[]{null, "", "with\nnewline", "with\rreturn", "nul\0byte", "x".repeat(1025)}) {
            Properties saved = new Properties();
            if (password != null) saved.setProperty("spring.datasource.password", password);
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(service, password));
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> copy.invoke(null, saved, new java.util.HashMap<>()));
            org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class, failure.getCause());
        }
    }

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
