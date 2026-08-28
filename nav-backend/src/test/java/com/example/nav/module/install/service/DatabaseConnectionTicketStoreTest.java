package com.example.nav.module.install.service;

import com.example.nav.common.config.DatabaseInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.model.DatabaseConnectionSpec;
import com.example.nav.module.install.model.DatabaseSchemaState;
import com.example.nav.module.install.model.DatabaseSslMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConnectionTicketStoreTest {

    @Test
    void ticketIs256BitSingleUseAndBoundToGeneration() {
        MutableClock clock = new MutableClock();
        DatabaseConnectionTicketStore store = store(clock);

        var issued = store.issue(spec(), DatabaseSchemaState.READY_UNINSTALLED,
                "c028d95a-dcb2-46e5-81ac-770c588ed4c8");
        assertTrue(issued.token().matches("^[0-9a-f]{64}$"));
        assertEquals("db-user", store.consume(issued.token()).spec().username());
        assertThrows(BusinessException.class, () -> store.consume(issued.token()));

        var stale = store.issue(spec(), DatabaseSchemaState.READY_UNINSTALLED,
                "c028d95a-dcb2-46e5-81ac-770c588ed4c8");
        store.advanceGeneration();
        assertThrows(BusinessException.class, () -> store.consume(stale.token()));
    }

    @Test
    void expiredTicketCannotBeConsumed() {
        MutableClock clock = new MutableClock();
        DatabaseConnectionTicketStore store = store(clock);
        var issued = store.issue(spec(), DatabaseSchemaState.EMPTY, null);

        clock.advanceSeconds(31);

        assertThrows(BusinessException.class, () -> store.consume(issued.token()));
    }

    @Test
    void atMostThreeOutstandingTicketsAreRetained() {
        MutableClock clock = new MutableClock();
        DatabaseConnectionTicketStore store = store(clock);
        store.issue(spec(), DatabaseSchemaState.EMPTY, null);
        store.issue(spec(), DatabaseSchemaState.EMPTY, null);
        store.issue(spec(), DatabaseSchemaState.EMPTY, null);

        assertThrows(BusinessException.class,
                () -> store.issue(spec(), DatabaseSchemaState.EMPTY, null));
        assertThrows(BusinessException.class, () -> store.consume("0".repeat(64)));
    }

    @Test
    void expiredTicketSecretIsEvictedWithoutAnotherRequest() throws Exception {
        MutableClock clock = new MutableClock();
        DatabaseInstallProperties properties = new DatabaseInstallProperties();
        properties.setTicketTtlSeconds(1);
        DatabaseConnectionTicketStore store = new DatabaseConnectionTicketStore(properties, clock, 1);
        try {
            store.issue(spec(), DatabaseSchemaState.EMPTY, null);
            assertEquals(1, store.activeTicketCount());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (store.activeTicketCount() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertEquals(0, store.activeTicketCount());
        } finally {
            store.shutdownExpiryExecutor();
        }
    }

    private DatabaseConnectionTicketStore store(Clock clock) {
        DatabaseInstallProperties properties = new DatabaseInstallProperties();
        properties.setTicketTtlSeconds(30);
        return new DatabaseConnectionTicketStore(properties, clock);
    }

    private DatabaseConnectionSpec spec() {
        return new DatabaseConnectionSpec(
                "database.example.com",
                5432,
                "navigation",
                "db-user",
                "Secret!Database2026",
                DatabaseSslMode.REQUIRE,
                null,
                java.util.List.of("203.0.113.10")
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-15T00:00:00Z");

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
