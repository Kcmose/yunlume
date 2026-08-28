package com.example.nav.common.config;

import com.example.nav.module.install.service.DatabaseConfigurationStore;
import com.example.nav.module.install.service.RedisConfigurationStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Rejects incomplete external Redis settings before a production instance is ready. */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductionRedisConfigurationValidator implements ApplicationRunner {

    private final ExternalRedisProperties properties;
    private final String cacheType;
    private final RedisConfigurationStore redisConfigurationStore;
    private final DatabaseConfigurationStore databaseConfigurationStore;

    public ProductionRedisConfigurationValidator(
            ExternalRedisProperties properties,
            @Value("${spring.cache.type:simple}") String cacheType,
            RedisConfigurationStore redisConfigurationStore,
            DatabaseConfigurationStore databaseConfigurationStore
    ) {
        this.properties = properties;
        this.cacheType = cacheType;
        this.redisConfigurationStore = redisConfigurationStore;
        this.databaseConfigurationStore = databaseConfigurationStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"redis".equalsIgnoreCase(cacheType == null ? "" : cacheType.trim())) {
            throw new IllegalStateException(
                    "External Redis configuration is invalid: CACHE_TYPE must be redis in the production profile");
        }
        if (redisConfigurationStore.hasInvalidOrPendingArtifact()) {
            throw new IllegalStateException(
                    "External Redis configuration is invalid: persisted state is incomplete or invalid");
        }
        if (redisConfigurationStore.isUnconfiguredSource()) {
            if (databaseConfigurationStore.hasCompletedMarker()) {
                throw new IllegalStateException(
                        "External Redis configuration is invalid: completed installation has no Redis configuration");
            }
            return;
        }
        properties.validateForProduction(cacheType);
    }
}
