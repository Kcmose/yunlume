package com.example.nav.module.publicdata;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionSystemException;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicDataCacheVersionTransactionTest {

    @Test
    void failedDatabaseCommitLeavesRedisAheadSoRetryAdvancesAgainAndConverges() {
        var dataSource = database("jdbc:h2:mem:generation-commit-failure;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS site_config");
        jdbc.execute("CREATE TABLE site_config (id BIGINT PRIMARY KEY, version INTEGER NOT NULL, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO site_config(id, version) VALUES (1, 12)");
        var failFirstCommit = new AtomicBoolean(true);
        var transactions = new DataSourceTransactionManager(dataSource) {
            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                if (failFirstCommit.getAndSet(false)) {
                    throw new TransactionSystemException("simulated database commit failure");
                }
                super.doCommit(status);
            }
        };
        transactions.setRollbackOnCommitFailure(true);
        var redisGeneration = new AtomicLong(19);
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenAnswer(invocation -> {
            long requested = Long.parseLong(invocation.getArgument(2));
            return redisGeneration.updateAndGet(current ->
                    current > requested ? Math.addExact(current, 1) : Math.max(current, requested));
        });
        var version = new PublicDataCacheVersion(mock(RedisCacheManager.class), redis,
                new PublicDataCacheGenerationStore(jdbc), transactions);

        assertThrows(TransactionSystemException.class,
                () -> version.current(PublicDataCacheNames.NAVIGATION));
        assertEquals(12, jdbc.queryForObject("SELECT version FROM site_config WHERE id = 1", Integer.class));
        assertEquals(20, redisGeneration.get());

        assertEquals("21", version.current(PublicDataCacheNames.NAVIGATION));
        assertEquals(21, jdbc.queryForObject("SELECT version FROM site_config WHERE id = 1", Integer.class));
        assertEquals(21, redisGeneration.get());
    }

    @Test
    void currentSuspendsOuterReadOnlyTransactionAndReconcilesInWritableRequiresNewTransaction() {
        var dataSource = database("jdbc:h2:mem:generation-read-only;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS site_config");
        jdbc.execute("CREATE TABLE site_config (id BIGINT PRIMARY KEY, version INTEGER NOT NULL, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO site_config(id, version) VALUES (1, 12)");
        var transactions = new DataSourceTransactionManager(dataSource);
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return 12L;
        });
        var version = new PublicDataCacheVersion(
                mock(RedisCacheManager.class), redis,
                new PublicDataCacheGenerationStore(jdbc), transactions);
        var outer = new TransactionTemplate(transactions);
        outer.setReadOnly(true);

        String generation = outer.execute(status -> version.current(PublicDataCacheNames.NAVIGATION));

        assertEquals("12", generation);
        assertEquals(12, jdbc.queryForObject("SELECT version FROM site_config WHERE id = 1", Integer.class));
    }

    @Test
    void differentCacheReconciliationCannotAdvancePostgresqlBetweenLockedReadAndFinalDecision() throws Exception {
        var dataSource = database("jdbc:h2:mem:generation-linearized;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS site_config");
        jdbc.execute("CREATE TABLE site_config (id BIGINT PRIMARY KEY, version INTEGER NOT NULL, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO site_config(id, version) VALUES (1, 12)");
        var transactions = new DataSourceTransactionManager(dataSource);
        var firstInsideRedis = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondReachedRedis = new CountDownLatch(1);
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> keys = invocation.getArgument(1);
            long requested = Long.parseLong(invocation.getArgument(2));
            if (keys.get(0).endsWith(PublicDataCacheNames.NAVIGATION)) {
                firstInsideRedis.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                return requested;
            }
            secondReachedRedis.countDown();
            return requested == 12 ? 20L : requested;
        });
        var first = new PublicDataCacheVersion(mock(RedisCacheManager.class), redis,
                new PublicDataCacheGenerationStore(jdbc), transactions);
        var second = new PublicDataCacheVersion(mock(RedisCacheManager.class), redis,
                new PublicDataCacheGenerationStore(jdbc), transactions);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var earlier = executor.submit(() -> first.current(PublicDataCacheNames.NAVIGATION));
            assertTrue(firstInsideRedis.await(5, TimeUnit.SECONDS));
            var later = executor.submit(() -> second.current(PublicDataCacheNames.SEARCH_ENGINES));

            assertFalse(secondReachedRedis.await(250, TimeUnit.MILLISECONDS),
                    "later cache reconciliation bypassed the singleton row lock");
            releaseFirst.countDown();

            assertEquals("12", earlier.get(5, TimeUnit.SECONDS));
            assertEquals("20", later.get(5, TimeUnit.SECONDS));
            assertEquals(20, jdbc.queryForObject("SELECT version FROM site_config WHERE id = 1", Integer.class));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private DataSource database(String h2Url) {
        String postgresUrl = System.getenv("PUBLIC_CACHE_PG_URL");
        if (postgresUrl == null || postgresUrl.isBlank()) {
            return new DriverManagerDataSource(h2Url, "sa", "");
        }
        return new DriverManagerDataSource(
                postgresUrl,
                System.getenv().getOrDefault("PUBLIC_CACHE_PG_USERNAME", "postgres"),
                System.getenv().getOrDefault("PUBLIC_CACHE_PG_PASSWORD", "postgres"));
    }
}
