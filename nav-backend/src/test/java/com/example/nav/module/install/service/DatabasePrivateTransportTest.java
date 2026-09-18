package com.example.nav.module.install.service;

import com.example.nav.common.config.DatabaseInstallProperties;
import com.example.nav.common.config.PersistedDatabaseEnvironmentPostProcessor;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.dto.DatabaseConnectionDTO;
import com.example.nav.module.install.dto.DatabaseConfigureDTO;
import com.example.nav.module.install.model.DatabaseConnectionSpec;
import com.example.nav.module.install.model.DatabaseSslMode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.nio.file.Files;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabasePrivateTransportTest {
    @TempDir Path directory;

    private DatabaseInstallProperties paths() {
        var paths = new DatabaseInstallProperties();
        paths.setSource(DatabaseInstallProperties.Source.UNCONFIGURED);
        paths.setConfigFile(directory.resolve("database.properties").toString());
        paths.setConfiguredMarkerFile(directory.resolve("database.configured").toString());
        paths.setCompletedMarkerFile(directory.resolve("install.completed").toString());
        paths.setCaCertificateFile(directory.resolve("postgresql-ca.pem").toString());
        paths.setAutoRestart(false);
        return paths;
    }

    private DatabaseSetupService service(DatabaseConfigurationStore store, DatabaseConnectionTicketStore tickets)
            throws SQLException {
        DataSource placeholder = mock(DataSource.class);
        when(placeholder.getConnection()).thenThrow(new SQLException("unconfigured"));
        return new DatabaseSetupService(mock(InstallAccessService.class), store, tickets, placeholder,
                mock(ConfigurableApplicationContext.class), paths());
    }

    private DatabaseConnectionDTO request(String host, DatabaseSslMode mode, boolean acknowledged, String ca) {
        return new DatabaseConnectionDTO(host, 5432, "navigation", "nav_app", "private-test-password",
                mode, ca, true, acknowledged);
    }

    private MockEnvironment environment() {
        var paths = paths();
        return new MockEnvironment().withProperty("NAV_DATABASE_CONFIG_FILE", paths.getConfigFile())
                .withProperty("NAV_DATABASE_CONFIGURED_MARKER_FILE", paths.getConfiguredMarkerFile())
                .withProperty("NAV_INSTALL_COMPLETED_MARKER_FILE", paths.getCompletedMarkerFile())
                .withProperty("NAV_DATABASE_CA_FILE", paths.getCaCertificateFile());
    }

    @Test
    void onlyExplicitlyAcknowledgedPrivateConnectionsAreAccepted() throws Exception {
        var service = service(new DatabaseConfigurationStore(paths()), mock(DatabaseConnectionTicketStore.class));
        for (String host : List.of("10.1.2.3", "172.18.0.2", "192.168.1.5", "fd12::5")) {
            DatabaseConnectionSpec spec = ReflectionTestUtils.invokeMethod(service, "normalize",
                    request(host, DatabaseSslMode.DISABLE, true, null));
            assertNotNull(spec);
            assertEquals(DatabaseSslMode.DISABLE, spec.sslMode());
        }
        for (String host : List.of("8.8.8.8", "127.0.0.1", "169.254.169.254", "100.100.100.200",
                "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fd00:ec2::254", "2001:4860:4860::8888")) {
            assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(service, "normalize",
                    request(host, DatabaseSslMode.DISABLE, true, null)), host);
        }
        assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(service, "normalize",
                request("172.18.0.2", DatabaseSslMode.DISABLE, false, null)));
        assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(service, "normalize",
                request("172.18.0.2", DatabaseSslMode.DISABLE, true, "stale CA")));
        assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(service, "normalize",
                request("172.18.0.2", DatabaseSslMode.PREFER, true, null)));
    }

    @Test
    void jdbcUsesPinnedNumericAddressAndChangedResolutionIsRejected() throws Exception {
        var service = service(new DatabaseConfigurationStore(paths()), mock(DatabaseConnectionTicketStore.class));
        var spec = new DatabaseConnectionSpec("db.internal", 5432, "navigation", "nav_app", "secret",
                DatabaseSslMode.DISABLE, null, List.of("172.18.0.2"));
        String url = ReflectionTestUtils.invokeMethod(service, "jdbcUrl", spec, null);
        assertTrue(url.startsWith("jdbc:postgresql://172.18.0.2:5432/navigation?sslmode=disable&"));
        var changed = new DatabaseConnectionSpec("172.18.0.3", 5432, "navigation", "nav_app", "secret",
                DatabaseSslMode.DISABLE, null, List.of("172.18.0.2"));
        assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "requireResolutionUnchanged", changed));
    }

    @Test
    void persistedPlaintextReloadsAndRejectsMissingChangedPublicAndUnpinnedTargets() throws Exception {
        var store = new DatabaseConfigurationStore(paths());
        var service = service(store, mock(DatabaseConnectionTicketStore.class));
        DatabaseConnectionSpec spec = ReflectionTestUtils.invokeMethod(service, "normalize",
                request("172.18.0.2", DatabaseSslMode.DISABLE, true, null));
        String url = ReflectionTestUtils.invokeMethod(service, "jdbcUrl", spec, null);
        String identity = "00b61475-8c0d-4d22-a08d-c144e989fc36";
        store.verifyWritable();
        store.beginConfiguration();
        store.saveExternal(spec, url, identity);
        store.markConfigured(identity);
        var env = environment();
        new PersistedDatabaseEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertEquals(url, env.getProperty("spring.datasource.url"));
        assertEquals(identity, env.getProperty("nav.database-config.expected-instance-id"));
        Properties original = new Properties();
        Path file = Path.of(paths().getConfigFile());
        try (var input = Files.newInputStream(file)) { original.load(input); }
        String[][] corruptions = {
                {"nav.database-config.private-host", ""},
                {"nav.database-config.private-host", "8.8.8.8"},
                {"nav.database-config.private-host", "172.18.0.3"},
                {"nav.database-config.private-addresses", ""},
                {"spring.datasource.url", url.replace("172.18.0.2", "8.8.8.8")},
                {"spring.datasource.url", url.replace("172.18.0.2", "db.internal")},
                {"spring.datasource.url", url + "&sslmode=require"},
                {"spring.datasource.url", url + "&sslrootcert=/tmp/ca.pem"},
        };
        for (String[] corruption : corruptions) {
            Properties bad = new Properties(); bad.putAll(original);
            bad.setProperty(corruption[0], corruption[1]);
            try (var output = Files.newOutputStream(file)) { bad.store(output, null); }
            assertThrows(RuntimeException.class, () -> new PersistedDatabaseEnvironmentPostProcessor()
                    .postProcessEnvironment(environment(), null), corruption[0]);
        }
        try (var output = Files.newOutputStream(file)) { original.store(output, null); }
        store.markCompleted(identity);
        assertDoesNotThrow(() -> new PersistedDatabaseEnvironmentPostProcessor()
                .postProcessEnvironment(environment(), null));
        assertFalse(store.isUnconfiguredSource());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PG_PRIVATE_TEST_HOST", matches = ".+")
    void realNonSslPostgresqlTestsInitializesAndRestoresPersistedConnection() throws Exception {
        String host = System.getenv("PG_PRIVATE_TEST_HOST");
        var store = new DatabaseConfigurationStore(paths());
        var tickets = new DatabaseConnectionTicketStore(paths());
        try {
            var service = service(store, tickets);
            var request = request(host, DatabaseSslMode.DISABLE, true, null);
            var badPassword = new DatabaseConnectionDTO(host, 5432, "navigation", "nav_app", "wrong-password",
                    DatabaseSslMode.DISABLE, null, false, true);
            assertThrows(BusinessException.class, () -> service.test(badPassword));
            assertTrue(store.isUnconfiguredSource());
            var tested = service.test(request);
            assertTrue(tested.requiresInitialization());
            var configured = service.configure(new DatabaseConfigureDTO(tested.connectionTicket(), true));
            assertTrue(configured.configured());
            assertTrue(configured.initialized());
            assertTrue(configured.restartRequired());
            assertThrows(BusinessException.class, () -> service.configure(
                    new DatabaseConfigureDTO(tested.connectionTicket(), true)));
            var env = environment();
            new PersistedDatabaseEnvironmentPostProcessor().postProcessEnvironment(env, null);
            var pool = new HikariConfig();
            pool.setJdbcUrl(env.getProperty("spring.datasource.url"));
            pool.setUsername(env.getProperty("spring.datasource.username"));
            pool.setPassword(env.getProperty("spring.datasource.password"));
            pool.setConnectionInitSql(env.getProperty("spring.datasource.hikari.connection-init-sql"));
            try (var datasource = new HikariDataSource(pool); var connection = datasource.getConnection();
                 var statement = connection.createStatement(); var result = statement.executeQuery("SHOW ssl")) {
                assertTrue(result.next());
                assertEquals("off", result.getString(1));
            }
        } finally { tickets.shutdownExpiryExecutor(); }
    }
}
