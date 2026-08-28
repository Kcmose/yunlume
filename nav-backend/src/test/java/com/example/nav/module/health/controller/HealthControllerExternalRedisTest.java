package com.example.nav.module.health.controller;

import com.example.nav.common.result.Result;
import com.example.nav.module.health.vo.HealthVO;
import com.example.nav.module.install.service.DatabaseConfigurationStore;
import com.example.nav.module.install.service.DatabaseIdentityService;
import com.example.nav.module.install.service.InstallService;
import com.example.nav.module.install.service.RedisConfigurationStore;
import com.example.nav.module.install.vo.InstallStatusVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthControllerExternalRedisTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private InstallService installService;
    @Mock
    private DatabaseConfigurationStore configurationStore;
    @Mock
    private DatabaseIdentityService databaseIdentityService;
    @Mock
    private RedisConfigurationStore redisConfigurationStore;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void unconfiguredDatabaseKeepsInstallerReachableWithoutProbingRedis() {
        when(configurationStore.isUnconfiguredSource()).thenReturn(true);
        when(installService.status()).thenReturn(
                new InstallStatusVO("DATABASE_REQUIRED", true, true, false));

        Result<HealthVO> result = controller().health();

        assertEquals("INSTALLING", result.data().status());
        verifyNoInteractions(jdbcTemplate, redisTemplateProvider, redisTemplate);
    }

    @Test
    void redisFailureKeepsFreshInstallerAliveButNotReady() {
        prepareConnectedDatabase();
        when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue())
                .thenThrow(new IllegalStateException("external Redis unavailable"));
        when(installService.status()).thenReturn(
                new InstallStatusVO("REQUIRED", true, true, true));

        Result<HealthVO> result = controller().health();

        assertEquals("INSTALLING", result.data().status());
    }

    @Test
    void redisFailureMakesCompletedInstanceHealthFailClosed() {
        prepareConnectedDatabase();
        when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue())
                .thenThrow(new IllegalStateException("external Redis unavailable"));
        when(installService.status()).thenReturn(
                new InstallStatusVO("COMPLETED", false, false, false));

        assertThrows(IllegalStateException.class, () -> controller().health());
    }

    @Test
    void redisRequiredKeepsFreshInstallerReachableWithoutAProbe() {
        when(redisConfigurationStore.isUnconfiguredSource()).thenReturn(true);
        when(installService.status()).thenReturn(
                new InstallStatusVO("REDIS_REQUIRED", true, true, false));

        Result<HealthVO> result = controller().health();

        assertEquals("INSTALLING", result.data().status());
        verifyNoInteractions(jdbcTemplate, redisTemplateProvider, redisTemplate);
    }

    @Test
    void deletedManagedArtifactsCannotMakeCompletedRuntimeLookHealthy() {
        when(redisConfigurationStore.isUnconfiguredSource()).thenReturn(true);
        when(installService.status()).thenReturn(
                new InstallStatusVO("COMPLETED", false, true, true));

        assertThrows(IllegalStateException.class, () -> controller().health());
        verifyNoInteractions(jdbcTemplate, redisTemplateProvider, redisTemplate);
    }

    @Test
    void publicHealthCoalescesSuccessfulRedisWritesForFiveSeconds() {
        prepareConnectedDatabase();
        when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AtomicReference<String> value = new AtomicReference<>();
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60))))
                .thenAnswer(invocation -> {
                    value.set(invocation.getArgument(1));
                    return true;
                });
        when(valueOperations.get(anyString())).thenAnswer(invocation -> value.get());
        when(redisTemplate.delete(anyString())).thenReturn(true);
        HealthController controller = controller();

        assertEquals("UP", controller.health().data().status());
        assertEquals("UP", controller.health().data().status());

        verify(valueOperations, times(1))
                .setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60)));
    }

    private void prepareConnectedDatabase() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(databaseIdentityService.ensureVerified()).thenReturn(true);
    }

    private HealthController controller() {
        return new HealthController(
                jdbcTemplate,
                redisTemplateProvider,
                "redis",
                installService,
                configurationStore,
                databaseIdentityService,
                redisConfigurationStore
        );
    }
}
