package com.example.nav.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.data.redis.cache.RedisCacheManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisCacheTransactionConfigurationTest {

    @Test
    void defersRedisCacheWritesAndEvictionsUntilTransactionCommit() {
        RedisCacheManager cacheManager = mock(RedisCacheManager.class);
        CacheManagerCustomizer<RedisCacheManager> customizer =
                new RedisCacheTransactionConfiguration().transactionAwareRedisCacheManager();

        customizer.customize(cacheManager);

        verify(cacheManager).setTransactionAware(true);
    }
}
