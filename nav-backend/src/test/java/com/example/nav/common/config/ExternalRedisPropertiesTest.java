package com.example.nav.common.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalRedisPropertiesTest {

    @Test
    void completeTlsConfigurationIsAccepted() {
        ExternalRedisProperties properties = validProperties();
        properties.setUsername("navigation-app");
        properties.setDatabase(2);

        assertDoesNotThrow(() -> properties.validateForProduction("redis"));
    }

    @Test
    void trustedPrivateNetworkMayExplicitlyDisableTls() {
        ExternalRedisProperties properties = validProperties();
        properties.setSslEnabled(false);

        assertDoesNotThrow(() -> properties.validateForProduction("redis"));
    }

    @Test
    void missingEndpointOrAuthenticationFailsClosedWithoutLeakingValues() {
        ExternalRedisProperties properties = validProperties();
        properties.setHost(" ");
        assertInvalid(properties, "REDIS_HOST");

        properties = validProperties();
        properties.setPassword("");
        assertInvalid(properties, "REDIS_PASSWORD");
    }

    @Test
    void unsafeRangesTimeoutsAndCacheFallbackAreRejected() {
        ExternalRedisProperties properties = validProperties();
        properties.setPort(0);
        assertInvalid(properties, "REDIS_PORT");

        properties = validProperties();
        properties.setDatabase(-1);
        assertInvalid(properties, "REDIS_DATABASE");

        properties = validProperties();
        properties.setReadTimeout(Duration.ofSeconds(61));
        assertInvalid(properties, "REDIS_READ_TIMEOUT");

        properties = validProperties();
        assertInvalid(properties, "simple", "CACHE_TYPE");
    }

    private ExternalRedisProperties validProperties() {
        ExternalRedisProperties properties = new ExternalRedisProperties();
        properties.setHost("redis.example.internal");
        properties.setPort(6380);
        properties.setPassword("not-logged-secret");
        properties.setDatabase(0);
        properties.setSslEnabled(true);
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setReadTimeout(Duration.ofSeconds(3));
        return properties;
    }

    private void assertInvalid(ExternalRedisProperties properties, String expectedField) {
        assertInvalid(properties, "redis", expectedField);
    }

    private void assertInvalid(
            ExternalRedisProperties properties,
            String cacheType,
            String expectedField
    ) {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> properties.validateForProduction(cacheType));
        assertTrue(exception.getMessage().contains(expectedField));
        assertTrue(!exception.getMessage().contains("not-logged-secret"));
    }
}
