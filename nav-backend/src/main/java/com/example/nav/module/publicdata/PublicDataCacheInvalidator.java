package com.example.nav.module.publicdata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;

/** Records invalidation durably in the business transaction, then mirrors it to Redis. */
@Slf4j
@Component
public class PublicDataCacheInvalidator {

    private final PublicDataCacheVersion version;
    private final PublicDataCacheGenerationStore generations;

    public PublicDataCacheInvalidator(
            CacheManager ignoredCacheManager,
            PublicDataCacheVersion version,
            PublicDataCacheGenerationStore generations
    ) {
        this.version = version;
        this.generations = generations;
    }

    public void invalidate(String... cacheNames) {
        invalidateRecorded(generations.advance(), cacheNames);
    }

    /** Used when the caller's business write already advanced site_config.version. */
    public void invalidateRecorded(long generation, String... cacheNames) {
        if (generation < 0 || generation > Integer.MAX_VALUE) {
            throw new IllegalStateException("Public cache generation is outside 0..2147483647");
        }
        String[] names = Arrays.copyOf(cacheNames, cacheNames.length);
        Runnable publication = () -> publishBestEffort(generation, names);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publication.run();
                }
            });
            return;
        }
        publication.run();
    }

    private void publishBestEffort(long generation, String[] names) {
        for (String cacheName : names) {
            try {
                version.advanceTo(cacheName, generation);
            } catch (RuntimeException exception) {
                // The database generation is authoritative and survives restart.
                log.warn("Public cache generation publication deferred for {}", cacheName);
            }
        }
    }
}
