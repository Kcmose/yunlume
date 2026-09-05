package com.example.nav.module.install.service;

import com.example.nav.common.config.WebInstallProperties;
import com.example.nav.module.install.vo.InstallEnvironmentVO;
import com.example.nav.module.install.vo.InstallStatusVO;
import com.example.nav.module.upload.config.UploadStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.ResultSet;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstallServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private InstallTransactionService transactionService;
    @Mock
    private InstallAccessService accessService;
    @Mock
    private DatabaseConfigurationStore configurationStore;
    @Mock
    private DatabaseIdentityService databaseIdentityService;
    @Mock
    private RedisConfigurationStore redisConfigurationStore;
    @Mock
    private ResultSet resultSet;

    @TempDir
    private Path temporaryDirectory;

    @Test
    @SuppressWarnings("rawtypes")
    void unavailableDatabaseProducesUnknownInsteadOfClaimingInstallationIsRequired() {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        InstallStatusVO status = service("", "redis").status();

        assertEquals("UNKNOWN", status.state());
        assertFalse(status.installationRequired());
        assertFalse(status.ready());
        verifyNoInteractions(redisTemplateProvider, passwordEncoder, transactionService);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void anonymousFreshStatusDoesNotProbeUploadOrRedis() throws Exception {
        when(resultSet.getLong("user_count")).thenReturn(0L);
        when(resultSet.getLong("site_config_count")).thenReturn(1L);
        when(resultSet.getLong("completed_count")).thenReturn(0L);
        when(resultSet.getLong("portable_import_guard_count")).thenReturn(1L);
        when(resultSet.getLong("portable_import_guard_id_one_count")).thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1)).mapRow(resultSet, 0));

        InstallStatusVO status = service("", "redis").status();

        assertEquals("REQUIRED", status.state());
        assertTrue(status.installationRequired());
        assertTrue(status.ready());
        verifyNoInteractions(redisTemplateProvider, passwordEncoder, transactionService);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void missingPortableImportGuardFailsRuntimeIntegrityClosed() throws Exception {
        assertRuntimeGuardRejected(0L, 0L);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void extraPortableImportGuardFailsRuntimeIntegrityClosed() throws Exception {
        assertRuntimeGuardRejected(2L, 1L);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void corruptPortableImportGuardFailsRuntimeIntegrityClosed() throws Exception {
        assertRuntimeGuardRejected(1L, 0L);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertRuntimeGuardRejected(long guardCount, long idOneCount) throws Exception {
        when(resultSet.getLong("user_count")).thenReturn(0L);
        when(resultSet.getLong("site_config_count")).thenReturn(1L);
        when(resultSet.getLong("completed_count")).thenReturn(0L);
        when(resultSet.getLong("portable_import_guard_count")).thenReturn(guardCount);
        when(resultSet.getLong("portable_import_guard_id_one_count")).thenReturn(idOneCount);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1)).mapRow(resultSet, 0));

        InstallStatusVO status = service("", "simple").status();

        assertEquals("UNKNOWN", status.state());
        assertFalse(status.installationRequired());
        assertFalse(status.ready());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void configuredDatabaseWithoutManagedRedisReportsRedisRequired() throws Exception {
        when(resultSet.getLong("user_count")).thenReturn(0L);
        when(resultSet.getLong("site_config_count")).thenReturn(1L);
        when(resultSet.getLong("completed_count")).thenReturn(0L);
        when(resultSet.getLong("portable_import_guard_count")).thenReturn(1L);
        when(resultSet.getLong("portable_import_guard_id_one_count")).thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1)).mapRow(resultSet, 0));
        when(redisConfigurationStore.isUnconfiguredSource()).thenReturn(true);

        InstallStatusVO status = service("", "redis").status();

        assertEquals("REDIS_REQUIRED", status.state());
        assertTrue(status.installationRequired());
        assertFalse(status.ready());
        verifyNoInteractions(redisTemplateProvider, passwordEncoder, transactionService);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void protectedEnvironmentCheckFailsClosedWhenExternalRedisIsUnavailable() throws Exception {
        when(resultSet.getLong("user_count")).thenReturn(0L);
        when(resultSet.getLong("site_config_count")).thenReturn(1L);
        when(resultSet.getLong("completed_count")).thenReturn(0L);
        when(resultSet.getLong("portable_import_guard_count")).thenReturn(1L);
        when(resultSet.getLong("portable_import_guard_id_one_count")).thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1)).mapRow(resultSet, 0));
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenReturn(1);
        when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue())
                .thenThrow(new IllegalStateException("external Redis unavailable"));

        InstallEnvironmentVO result = service(temporaryDirectory.toString(), "redis")
                .check();

        assertFalse(result.ready());
        assertFalse(result.checks().redis().ok());
        assertEquals("Redis 不可用", result.checks().redis().message());
    }

    private InstallService service(String uploadDirectory, String cacheType) {
        WebInstallProperties properties = new WebInstallProperties();
        properties.setEnabled(true);
        UploadStorageProperties upload = new UploadStorageProperties();
        upload.setDirectory(uploadDirectory);
        return new InstallService(
                properties,
                upload,
                jdbcTemplate,
                redisTemplateProvider,
                cacheType,
                passwordEncoder,
                transactionService,
                accessService,
                configurationStore,
                databaseIdentityService,
                redisConfigurationStore,
                ""
        );
    }
}
