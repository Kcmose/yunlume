package com.example.nav.common.config;

import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheManager;

@Configuration(proxyBeanMethods = false)
public class RedisCacheTransactionConfiguration {

    @Bean
    CacheManagerCustomizer<RedisCacheManager> transactionAwareRedisCacheManager() {
        return cacheManager -> cacheManager.setTransactionAware(true);
    }
}
