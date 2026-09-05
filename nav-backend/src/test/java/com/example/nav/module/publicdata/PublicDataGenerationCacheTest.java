package com.example.nav.module.publicdata;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDataGenerationCacheTest {

    @Test
    void invalidKeysDoNotThrowEvictCurrentValuesOrConsumeCapacity() {
        var cache = new PublicDataGenerationCache(PublicDataCacheNames.NAVIGATION, true);
        cache.put("7", "current");

        for (Object key : Arrays.asList(null, 8, "text", "-1", "01", "1.5", "2147483648", "999999999999999999999")) {
            cache.put(key, "invalid");
            cache.putIfAbsent(key, "invalid");
            assertThat(cache.get(key)).isNull();
            cache.evict(key);
            assertThat(cache.evictIfPresent(key)).isFalse();
        }

        assertThat(cache.getNativeCache()).containsOnlyKeys("7");
        assertThat(cache.get("7", String.class)).isEqualTo("current");
    }

    @Test
    void nullableValuesAndDelayedPublicationKeepCurrentGeneration() {
        var cache = new PublicDataGenerationCache(PublicDataCacheNames.NAVIGATION, true);
        cache.put("10", null);
        assertThat(cache.get("10")).isNotNull();
        assertThat(cache.get("10").get()).isNull();

        cache.advanceTo(11);
        cache.putIfAbsent("11", "current");
        cache.advanceTo(10);
        cache.put("10", "late");
        cache.putIfAbsent("10", "late");

        assertThat(cache.get("10")).isNull();
        assertThat(cache.getNativeCache()).containsOnlyKeys("11");
        assertThat(cache.get("11", String.class)).isEqualTo("current");
    }
}
