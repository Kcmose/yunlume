package com.example.nav.module.publicdata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "simple")
public class PublicDataSimpleCacheConfiguration {

    private static final Set<String> PUBLIC_CACHES = Set.of(
            PublicDataCacheNames.SITE_CONFIG, PublicDataCacheNames.NAVIGATION,
            PublicDataCacheNames.SEARCH_ENGINES, PublicDataCacheNames.CUSTOM_LINKS);

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    ConcurrentMapCacheManager publicDataSimpleCacheManager() {
        return new ConcurrentMapCacheManager() {
            @Override
            protected Cache createConcurrentMapCache(String name) {
                return PUBLIC_CACHES.contains(name)
                        ? new PublicDataGenerationCache(name, isAllowNullValues())
                        : super.createConcurrentMapCache(name);
            }
        };
    }
}
