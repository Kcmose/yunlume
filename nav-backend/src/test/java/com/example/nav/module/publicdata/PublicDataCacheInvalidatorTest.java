package com.example.nav.module.publicdata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicDataCacheInvalidatorTest {

    @Test
    void recordedGenerationRejectsOverflowBeforeSchedulingPublication() {
        PublicDataCacheInvalidator invalidator = new PublicDataCacheInvalidator(
                mock(CacheManager.class), mock(PublicDataCacheVersion.class),
                mock(PublicDataCacheGenerationStore.class));

        assertThrows(IllegalStateException.class, () -> invalidator.invalidateRecorded(
                2147483648L, PublicDataCacheNames.NAVIGATION));
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void recordsDurableGenerationInsideTransactionAndPublishesOnlyAfterCommit() {
        CacheManager cacheManager = mock(CacheManager.class);
        PublicDataCacheVersion version = mock(PublicDataCacheVersion.class);
        PublicDataCacheGenerationStore generations = mock(PublicDataCacheGenerationStore.class);
        when(generations.advance()).thenReturn(42L);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        new PublicDataCacheInvalidator(cacheManager, version, generations)
                .invalidate(PublicDataCacheNames.NAVIGATION);

        verify(generations).advance();
        verify(version, never()).advanceTo(PublicDataCacheNames.NAVIGATION, 42L);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(version).advanceTo(PublicDataCacheNames.NAVIGATION, 42L);
    }

    @Test
    void redisFailureAfterCommitNeverEscapesCommittedBusinessOperation() {
        CacheManager cacheManager = mock(CacheManager.class);
        PublicDataCacheVersion version = mock(PublicDataCacheVersion.class);
        PublicDataCacheGenerationStore generations = mock(PublicDataCacheGenerationStore.class);
        when(generations.advance()).thenReturn(7L);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(version).advanceTo(PublicDataCacheNames.NAVIGATION, 7L);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        PublicDataCacheInvalidator invalidator =
                new PublicDataCacheInvalidator(cacheManager, version, generations);

        invalidator.invalidate(PublicDataCacheNames.NAVIGATION);
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().get(0);

        assertDoesNotThrow(synchronization::afterCommit);
    }

    @Test
    void importCanPublishGenerationAlreadyCommittedWithItsBusinessRows() {
        CacheManager cacheManager = mock(CacheManager.class);
        PublicDataCacheVersion version = mock(PublicDataCacheVersion.class);
        PublicDataCacheGenerationStore generations = mock(PublicDataCacheGenerationStore.class);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        PublicDataCacheInvalidator invalidator =
                new PublicDataCacheInvalidator(cacheManager, version, generations);

        invalidator.invalidateRecorded(19L,
                PublicDataCacheNames.SITE_CONFIG, PublicDataCacheNames.NAVIGATION);

        verify(generations, never()).advance();
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        var order = inOrder(version);
        order.verify(version).advanceTo(PublicDataCacheNames.SITE_CONFIG, 19L);
        order.verify(version).advanceTo(PublicDataCacheNames.NAVIGATION, 19L);
    }
}
