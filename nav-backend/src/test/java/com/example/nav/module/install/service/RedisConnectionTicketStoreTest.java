package com.example.nav.module.install.service;

import com.example.nav.common.config.RedisInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.model.RedisConnectionSpec;
import com.example.nav.module.install.model.RedisTlsMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisConnectionTicketStoreTest {

    @Test
    void ticketIs256BitSingleUseAndBoundToGeneration() {
        MutableClock clock = new MutableClock();
        RedisConnectionTicketStore store = store(clock);
        try {
            var issued = store.issue(spec(), "a".repeat(64), databaseInstanceId());
            assertTrue(issued.token().matches("^[0-9a-f]{64}$"));
            assertEquals("redis-user", store.consume(issued.token()).spec().username());
            assertThrows(BusinessException.class, () -> store.consume(issued.token()));

            var stale = store.issue(spec(), "a".repeat(64), databaseInstanceId());
            store.advanceGeneration();
            assertThrows(BusinessException.class, () -> store.consume(stale.token()));
        } finally {
            store.shutdownExpiryExecutor();
        }
    }

    @Test
    void expiredTicketCannotBeConsumedAndAtMostThreeRemainOutstanding() {
        MutableClock clock = new MutableClock();
        RedisConnectionTicketStore store = store(clock);
        try {
            var expired = store.issue(spec(), "a".repeat(64), databaseInstanceId());
            clock.advanceSeconds(31);
            assertThrows(BusinessException.class, () -> store.consume(expired.token()));

            store.issue(spec(), "b".repeat(64), databaseInstanceId());
            store.issue(spec(), "c".repeat(64), databaseInstanceId());
            store.issue(spec(), "d".repeat(64), databaseInstanceId());
            assertThrows(BusinessException.class,
                    () -> store.issue(spec(), "e".repeat(64), databaseInstanceId()));
        } finally {
            store.shutdownExpiryExecutor();
        }
    }

    private RedisConnectionTicketStore store(Clock clock) {
        RedisInstallProperties properties = new RedisInstallProperties();
        properties.setTicketTtlSeconds(30);
        return new RedisConnectionTicketStore(properties, clock);
    }

    private RedisConnectionSpec spec() {
        return new RedisConnectionSpec(
                "redis.example.com", 6379, "redis-user", "Secret!Redis2026", 0,
                RedisTlsMode.SYSTEM, null, Duration.ofSeconds(3), Duration.ofSeconds(3),
                List.of("203.0.113.20"));
    }

    private String databaseInstanceId() {
        return "c028d95a-dcb2-46e5-81ac-770c588ed4c8";
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-16T00:00:00Z");

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
