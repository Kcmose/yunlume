package com.example.nav.common.config;

import com.example.nav.module.install.service.DatabaseConfigurationStore;
import com.example.nav.module.install.service.RedisConfigurationStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductionRedisConfigurationValidatorTest {

    @Test
    void genuinelyFreshUnconfiguredRedisIsAllowedToStartInstaller() {
        ExternalRedisProperties properties = mock(ExternalRedisProperties.class);
        RedisConfigurationStore redisStore = mock(RedisConfigurationStore.class);
        DatabaseConfigurationStore databaseStore = mock(DatabaseConfigurationStore.class);
        when(redisStore.isUnconfiguredSource()).thenReturn(true);

        ProductionRedisConfigurationValidator validator = new ProductionRedisConfigurationValidator(
                properties, "redis", redisStore, databaseStore);

        assertDoesNotThrow(() -> validator.run(mock(ApplicationArguments.class)));
    }

    @Test
    void completedInstallWithoutManagedRedisFailsClosed() {
        ExternalRedisProperties properties = mock(ExternalRedisProperties.class);
        RedisConfigurationStore redisStore = mock(RedisConfigurationStore.class);
        DatabaseConfigurationStore databaseStore = mock(DatabaseConfigurationStore.class);
        when(redisStore.isUnconfiguredSource()).thenReturn(true);
        when(databaseStore.hasCompletedMarker()).thenReturn(true);

        ProductionRedisConfigurationValidator validator = new ProductionRedisConfigurationValidator(
                properties, "redis", redisStore, databaseStore);

        assertThrows(IllegalStateException.class,
                () -> validator.run(mock(ApplicationArguments.class)));
    }

    @Test
    void legacyEnvironmentStillUsesProductionValidation() {
        ExternalRedisProperties properties = mock(ExternalRedisProperties.class);
        RedisConfigurationStore redisStore = mock(RedisConfigurationStore.class);
        DatabaseConfigurationStore databaseStore = mock(DatabaseConfigurationStore.class);

        new ProductionRedisConfigurationValidator(properties, "redis", redisStore, databaseStore)
                .run(mock(ApplicationArguments.class));

        verify(properties).validateForProduction("redis");
    }
}
