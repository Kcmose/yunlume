package com.example.nav.module.install.service;

import com.example.nav.common.config.DatabaseInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.dto.DatabaseConnectionDTO;
import com.example.nav.module.install.model.DatabaseSslMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseSetupServiceValidationTest {

    @Mock InstallAccessService accessService;
    @Mock DatabaseConfigurationStore configurationStore;
    @Mock DatabaseConnectionTicketStore ticketStore;
    @Mock DataSource currentDataSource;
    @Mock ConfigurableApplicationContext applicationContext;

    private DatabaseSetupService service;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseInstallProperties properties = new DatabaseInstallProperties();
        when(configurationStore.isUnconfiguredSource()).thenReturn(true);
        when(currentDataSource.getConnection()).thenThrow(new SQLException("placeholder unavailable"));
        service = new DatabaseSetupService(
                accessService, configurationStore, ticketStore, currentDataSource,
                applicationContext, properties);
    }

    @Test
    void externalDatabaseCannotDisableTls() {
        assertThrows(BusinessException.class, () -> service.test(
                external("8.8.8.8", "Database!Secret2026", DatabaseSslMode.DISABLE,
                        null, false)));
    }

    @Test
    void hostAndPasswordControlCharactersAreRejectedBeforeConnecting() {
        assertThrows(BusinessException.class, () -> service.test(
                external("db.example.com/other", "Database!Secret2026",
                        DatabaseSslMode.REQUIRE, null, true)));
        assertThrows(BusinessException.class, () -> service.test(
                external("8.8.8.8", "Database!Secret2026\nleak",
                        DatabaseSslMode.REQUIRE, null, true)));
    }

    @Test
    void verifiedTlsRequiresAValidCaCertificate() {
        assertThrows(BusinessException.class, () -> service.test(
                external("8.8.8.8", "Database!Secret2026",
                        DatabaseSslMode.VERIFY_FULL, "not a certificate", false)));
    }

    @Test
    void requireTlsNeedsExplicitRiskAcknowledgement() {
        assertThrows(BusinessException.class, () -> service.test(
                external("8.8.8.8", "Database!Secret2026",
                        DatabaseSslMode.REQUIRE, null, false)));
    }

    private DatabaseConnectionDTO external(
            String host,
            String password,
            DatabaseSslMode sslMode,
            String caCertificate,
            boolean acknowledge
    ) {
        return new DatabaseConnectionDTO(
                host,
                5432,
                "navigation",
                "nav-user",
                password,
                sslMode,
                caCertificate,
                acknowledge
        );
    }
}
