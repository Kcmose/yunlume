package com.example.nav.module.publicdata;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicDataCacheVersionTest {

    @Test
    void nonRedisPublicationStillRejectsNonCanonicalGeneration() {
        PublicDataCacheVersion version = new PublicDataCacheVersion(
                mock(CacheManager.class), mock(StringRedisTemplate.class),
                mock(PublicDataCacheGenerationStore.class));

        assertThrows(IllegalStateException.class,
                () -> version.advanceTo(PublicDataCacheNames.NAVIGATION, -1));
        assertThrows(IllegalStateException.class,
                () -> version.advanceTo(PublicDataCacheNames.NAVIGATION, 2147483648L));
    }

    @Test
    void currentReconcilesDurableAuthorityBeforeReturningCacheKey() {
        RedisCacheManager cacheManager = mock(RedisCacheManager.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        PublicDataCacheGenerationStore generations = mock(PublicDataCacheGenerationStore.class);
        when(generations.lockCurrent()).thenReturn(12L);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenReturn(12L);

        String current = new PublicDataCacheVersion(cacheManager, redis, generations)
                .current(PublicDataCacheNames.NAVIGATION);

        assertEquals("12", current);
        verify(generations).lockCurrent();
        verify(generations, never()).advanceToWhileLocked(anyLong());
    }

    @Test
    void redisAheadAdvancesPastBothAuthoritiesAndPersistsBeforeReturning() {
        RedisCacheManager cacheManager = mock(RedisCacheManager.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        PublicDataCacheGenerationStore generations = mock(PublicDataCacheGenerationStore.class);
        when(generations.lockCurrent()).thenReturn(12L);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenReturn(20L, 20L);
        when(generations.advanceToWhileLocked(20L)).thenReturn(20L);

        String current = new PublicDataCacheVersion(cacheManager, redis, generations)
                .current(PublicDataCacheNames.NAVIGATION);

        assertEquals("20", current);
        verify(generations).advanceToWhileLocked(20L);
    }

    @Test
    void redisAdvancingBetweenDatabaseOperationsIsCaughtByBoundedReconciliationLoop() {
        RedisCacheManager cacheManager = mock(RedisCacheManager.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        PublicDataCacheGenerationStore generations = mock(PublicDataCacheGenerationStore.class);
        when(generations.lockCurrent()).thenReturn(12L);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenReturn(20L, 22L, 22L);
        when(generations.advanceToWhileLocked(20L)).thenReturn(20L);
        when(generations.advanceToWhileLocked(22L)).thenReturn(22L);

        String current = new PublicDataCacheVersion(cacheManager, redis, generations)
                .current(PublicDataCacheNames.NAVIGATION);

        assertEquals("22", current);
        verify(generations).advanceToWhileLocked(20L);
        verify(generations).advanceToWhileLocked(22L);
        verify(redis, times(3)).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString());
    }

    @Test
    void continuouslyRacingRedisFailsClosedWithoutReturningUndurableGeneration() {
        RedisCacheManager cacheManager = mock(RedisCacheManager.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        PublicDataCacheGenerationStore generations = mock(PublicDataCacheGenerationStore.class);
        when(generations.lockCurrent()).thenReturn(1L);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenReturn(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        when(generations.advanceToWhileLocked(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(IllegalStateException.class, () ->
                new PublicDataCacheVersion(cacheManager, redis, generations)
                        .current(PublicDataCacheNames.NAVIGATION));
    }

    @Test
    void redisAheadAtDatabaseLimitFailsClosedWithoutReusingOrOverflowing() {
        RedisCacheManager cacheManager = mock(RedisCacheManager.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        PublicDataCacheGenerationStore generations = mock(PublicDataCacheGenerationStore.class);
        when(generations.lockCurrent()).thenReturn(12L);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenThrow(new IllegalStateException("invalid cache generation overflow"));

        assertThrows(IllegalStateException.class, () ->
                new PublicDataCacheVersion(cacheManager, redis, generations)
                        .current(PublicDataCacheNames.NAVIGATION));
        verify(generations, never()).advanceToWhileLocked(anyLong());
    }

    @Test
    void currentFailsClosedWhenRedisCannotBeReconciled() {
        CacheManager cacheManager = mock(RedisCacheManager.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        PublicDataCacheGenerationStore generations = mock(PublicDataCacheGenerationStore.class);
        when(generations.lockCurrent()).thenReturn(13L);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(IllegalStateException.class, () ->
                new PublicDataCacheVersion(cacheManager, redis, generations)
                        .current(PublicDataCacheNames.NAVIGATION));
    }
}
