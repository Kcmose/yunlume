package com.example.nav.module.publicdata;

import com.example.nav.common.redis.RedisProductionLua;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Component("publicDataCacheVersion")
public class PublicDataCacheVersion {

    private static final String REDIS_KEY_PREFIX = "nav:public-cache-version:";
    private static final int MAX_RECONCILIATION_ATTEMPTS = 8;
    private static final DefaultRedisScript<Long> ADVANCE_MONOTONIC = new DefaultRedisScript<>(
            RedisProductionLua.script(RedisProductionLua.ADVANCE_GENERATION, "advance_generation"), Long.class);

    private final CacheManager cacheManager;
    private final StringRedisTemplate redisTemplate;
    private final PublicDataCacheGenerationStore generations;
    private final TransactionTemplate reconciliationTransaction;

    PublicDataCacheVersion(
            CacheManager cacheManager,
            StringRedisTemplate redisTemplate,
            PublicDataCacheGenerationStore generations
    ) {
        this(cacheManager, redisTemplate, generations, null);
    }

    @Autowired
    public PublicDataCacheVersion(
            CacheManager cacheManager,
            StringRedisTemplate redisTemplate,
            PublicDataCacheGenerationStore generations,
            PlatformTransactionManager transactionManager
    ) {
        this.cacheManager = cacheManager;
        this.redisTemplate = redisTemplate;
        this.generations = generations;
        if (transactionManager == null) {
            this.reconciliationTransaction = null;
        } else {
            this.reconciliationTransaction = new TransactionTemplate(transactionManager);
            this.reconciliationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.reconciliationTransaction.setReadOnly(false);
            this.reconciliationTransaction.setName("public-cache-generation-reconciliation");
        }
    }

    /**
     * Reads durable authority first and reconciles Redis before returning a key.
     * Redis failure therefore fails closed instead of trusting an old cache key.
     */
    public String current(String cacheName) {
        if (!(cacheManager instanceof RedisCacheManager)) {
            return Long.toString(generations.current());
        }
        Supplier<String> reconciliation = () -> reconcileWhileLocked(cacheName);
        if (reconciliationTransaction == null) return reconciliation.get();
        return Objects.requireNonNull(reconciliationTransaction.execute(status -> reconciliation.get()));
    }

    /**
     * The successful REQUIRES_NEW commit is the linearization point. The singleton row remains
     * locked from the authoritative read through the final Redis equality decision, so every
     * legitimate generation writer is ordered before or after the returned cache key.
     */
    private String reconcileWhileLocked(String cacheName) {
        long durable = generations.lockCurrent();
        for (int attempt = 0; attempt < MAX_RECONCILIATION_ATTEMPTS; attempt++) {
            long reconciled = advanceTo(cacheName, durable);
            if (reconciled == durable) return Long.toString(durable);
            if (reconciled < durable) {
                throw new IllegalStateException("Redis cache generation moved backwards");
            }
            long database = generations.advanceToWhileLocked(reconciled);
            if (database < reconciled) {
                throw new IllegalStateException("Database cache generation reconciliation failed");
            }
            durable = database;
        }
        throw new IllegalStateException("Redis cache generation kept racing durable authority");
    }

    public static String advanceScriptSource() {
        return ADVANCE_MONOTONIC.getScriptAsString();
    }

    public long advanceTo(String cacheName, long generation) {
        if (generation < 0 || generation > Integer.MAX_VALUE) {
            throw new IllegalStateException("Public cache generation is outside 0..2147483647");
        }
        if (!(cacheManager instanceof RedisCacheManager)) return generation;
        Long reconciled = redisTemplate.execute(
                ADVANCE_MONOTONIC,
                List.of(REDIS_KEY_PREFIX + cacheName),
                Long.toString(generation));
        if (reconciled == null) throw new IllegalStateException("Redis cache generation reconciliation failed");
        return reconciled;
    }
}
