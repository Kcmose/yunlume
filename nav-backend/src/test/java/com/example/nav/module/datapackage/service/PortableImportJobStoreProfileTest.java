package com.example.nav.module.datapackage.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PortableImportJobStoreProfileTest {

    @Test
    void redisStoreIsOnlyEnabledForRedisCacheMode() {
        ConditionalOnProperty condition = RedisPortableImportJobStore.class
                .getAnnotation(ConditionalOnProperty.class);

        assertNotNull(condition);
        assertEquals("spring.cache.type", condition.name()[0]);
        assertEquals("redis", condition.havingValue());
    }

    @Test
    void inMemoryStoreIsAvailableWhenRedisCacheModeIsNotSelected() {
        ConditionalOnExpression condition = InMemoryPortableImportJobStore.class
                .getAnnotation(ConditionalOnExpression.class);

        assertNotNull(condition);
        assertEquals("'${spring.cache.type:simple}' != 'redis'", condition.value());
    }
}
