package com.example.nav.module.datapackage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableImportCommitStoreTest {

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private PortableImportCommitStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:portable_commit_store;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS portable_import_operation");
        jdbc.execute("DROP TABLE IF EXISTS portable_import_guard");
        jdbc.execute("CREATE TABLE portable_import_guard (id INTEGER PRIMARY KEY)");
        jdbc.update("INSERT INTO portable_import_guard (id) VALUES (1)");
        jdbc.execute("""
                CREATE TABLE portable_import_operation (
                  job_id VARCHAR(64) PRIMARY KEY,
                  preview_token VARCHAR(64) NOT NULL UNIQUE,
                  user_id BIGINT NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  started_at TIMESTAMP NOT NULL,
                  committed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  site_version INTEGER NOT NULL
                )
                """);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new PortableImportCommitStore(jdbc);
    }

    @Test
    void commitMarkerIsAtomicWithTheBusinessTransaction() {
        Instant created = Instant.parse("2026-09-04T00:00:00Z");
        transaction.executeWithoutResult(status -> {
            store.lockWriter();
            store.recordCommitted("rolled-back", "preview-r", 7L, created, created.plusSeconds(1), 2);
            status.setRollbackOnly();
        });
        transaction.executeWithoutResult(status -> {
            store.lockWriter();
            store.recordCommitted("committed", "preview-c", 8L, created, created.plusSeconds(2), 3);
        });

        assertTrue(store.findByJobId("rolled-back").isEmpty());
        PortableImportCommitStore.CommittedImport committed = store.findByJobId("committed").orElseThrow();
        assertEquals(8L, committed.userId());
        assertEquals("preview-c", committed.previewToken());
        assertEquals(3, committed.siteVersion());
    }

    @Test
    void databaseGuardSerializesIndependentWriters() throws Exception {
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondAcquired = new AtomicBoolean();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                store.lockWriter();
                firstHasLock.countDown();
                await(releaseFirst);
            }));
            assertTrue(firstHasLock.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                store.lockWriter();
                secondAcquired.set(true);
            }));

            Thread.sleep(150);
            assertFalse(secondAcquired.get(), "second writer must wait for the DB-authoritative guard");
            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertTrue(secondAcquired.get());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
