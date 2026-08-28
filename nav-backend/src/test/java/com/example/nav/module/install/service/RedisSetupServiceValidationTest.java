package com.example.nav.module.install.service;

import com.example.nav.common.config.DatabaseInstallProperties;
import com.example.nav.common.config.RedisInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.dto.RedisConfigureDTO;
import com.example.nav.module.install.dto.RedisConnectionDTO;
import com.example.nav.module.install.model.RedisTlsMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSetupServiceValidationTest {

    private static final String INSTANCE_ID = "e38440cb-07d9-4fdf-9800-5a4ef185ee61";

    @Mock InstallAccessService accessService;
    @Mock DatabaseConfigurationStore databaseConfigurationStore;
    @Mock DatabaseIdentityService databaseIdentityService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock RedisConfigurationStore redisConfigurationStore;
    @Mock RedisConnectionVerifier connectionVerifier;
    @Mock ConfigurableApplicationContext applicationContext;
    @Mock ResultSet resultSet;

    private RedisConnectionTicketStore ticketStore;
    private RedisSetupService service;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() throws Exception {
        RedisInstallProperties redisProperties = new RedisInstallProperties();
        redisProperties.setTicketTtlSeconds(60);
        redisProperties.setAutoRestart(false);
        ticketStore = new RedisConnectionTicketStore(redisProperties);
        DatabaseInstallProperties databaseProperties = new DatabaseInstallProperties();
        when(databaseConfigurationStore.hasPersistedConnection()).thenReturn(true);
        when(databaseConfigurationStore.configuredInstanceId()).thenReturn(INSTANCE_ID);
        when(databaseIdentityService.isIdentityRequired()).thenReturn(true);
        when(databaseIdentityService.refresh()).thenReturn(true);
        when(redisConfigurationStore.isUnconfiguredSource()).thenReturn(true);
        when(resultSet.getString("install_instance_id")).thenReturn(INSTANCE_ID);
        when(resultSet.getBoolean("installed")).thenReturn(false);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1))
                        .mapRow(resultSet, 0));
        service = new RedisSetupService(
                accessService,
                databaseConfigurationStore,
                databaseIdentityService,
                jdbcTemplate,
                redisConfigurationStore,
                ticketStore,
                connectionVerifier,
                applicationContext,
                redisProperties,
                databaseProperties);
    }

    @AfterEach
    void tearDown() {
        ticketStore.shutdownExpiryExecutor();
    }

    @Test
    void testAndConfigureRequireEnabledInstallerAndCommitOnlyAfterRetest() {
        var tested = service.test(systemTlsDto());
        assertTrue(tested.ok());
        assertTrue(tested.connectionTicket().matches("^[0-9a-f]{64}$"));

        var configured = service.configure(
                new RedisConfigureDTO(tested.connectionTicket()));

        assertTrue(configured.configured());
        assertFalse(configured.restartRequired());
        verify(accessService, org.mockito.Mockito.times(2))
                .requireEnabled();
        verify(connectionVerifier, org.mockito.Mockito.times(2)).verifyReadWrite(any());
        verify(redisConfigurationStore).beginConfiguration(eq(INSTANCE_ID), anyString());
        verify(redisConfigurationStore).saveExternal(any(), anyString(), eq(INSTANCE_ID));
        verify(redisConfigurationStore).markConfigured(eq(INSTANCE_ID), anyString());
    }

    @Test
    void databaseCompletionFactsSealInstallerAndRepairLocalMarker() throws Exception {
        when(resultSet.getBoolean("installed")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.test(systemTlsDto()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(databaseConfigurationStore).markCompleted(INSTANCE_ID);
        verifyNoInteractions(connectionVerifier);
    }

    @Test
    void configureRechecksDatabaseCompletionBeforeConsumingOrWriting() throws Exception {
        var tested = service.test(systemTlsDto());
        when(resultSet.getBoolean("installed")).thenReturn(false, true);

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.configure(new RedisConfigureDTO(tested.connectionTicket())));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(databaseConfigurationStore).markCompleted(INSTANCE_ID);
        verify(redisConfigurationStore, never()).beginConfiguration(anyString(), anyString());
    }

    @Test
    void liveDatabaseUuidMismatchIsRejectedBeforeCandidateConnection() throws Exception {
        when(resultSet.getString("install_instance_id"))
                .thenReturn("4b9b020d-95cb-4754-906e-94f66a00a413");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.test(systemTlsDto()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verifyNoInteractions(connectionVerifier);
    }

    @Test
    void plaintextRedisRequiresExplicitAcknowledgementAndPrivateResolution() {
        RedisConnectionDTO noAcknowledgement = new RedisConnectionDTO(
                "10.23.45.67", 6379, "", "Redis!Secret2026", 0,
                RedisTlsMode.DISABLED, null, false, 3, 3);
        assertThrows(BusinessException.class,
                () -> service.test(noAcknowledgement));

        RedisConnectionDTO publicPlaintext = new RedisConnectionDTO(
                "203.0.113.20", 6379, "", "Redis!Secret2026", 0,
                RedisTlsMode.DISABLED, null, true, 3, 3);
        assertThrows(BusinessException.class,
                () -> service.test(publicPlaintext));
        verifyNoInteractions(connectionVerifier);
    }

    @Test
    void invalidCaTimeoutAndCredentialTextAreRejectedWithoutDisclosure() {
        RedisConnectionDTO invalidCa = new RedisConnectionDTO(
                "203.0.113.20", 6380, "nav-user", "Redis!Secret2026", 0,
                RedisTlsMode.CUSTOM_CA, "not-a-certificate", false, 3, 3);
        assertThrows(BusinessException.class,
                () -> service.test(invalidCa));

        RedisConnectionDTO excessiveTimeout = new RedisConnectionDTO(
                "203.0.113.20", 6380, "nav-user", "Redis!Secret2026", 0,
                RedisTlsMode.SYSTEM, null, false, 11, 3);
        assertThrows(BusinessException.class,
                () -> service.test(excessiveTimeout));

        RedisConnectionDTO secret = systemTlsDto();
        assertFalse(secret.toString().contains("Redis!Secret2026"));
        assertFalse(secret.toString().contains("203.0.113.20"));
        verify(connectionVerifier, never()).verifyReadWrite(any());
    }

    private RedisConnectionDTO systemTlsDto() {
        return new RedisConnectionDTO(
                "203.0.113.20", 6380, "nav-user", "Redis!Secret2026", 0,
                RedisTlsMode.SYSTEM, null, false, 3, 3);
    }
}
