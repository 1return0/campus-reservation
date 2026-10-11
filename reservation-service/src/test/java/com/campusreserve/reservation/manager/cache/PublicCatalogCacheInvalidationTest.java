package com.campusreserve.reservation.manager.cache;

import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class PublicCatalogCacheInvalidationTest {
    @Test
    void study_room_window_cache_is_evicted_only_after_commit() {
        var manager = new ConcurrentMapCacheManager("study-room-windows");
        var cache = manager.getCache("study-room-windows");
        cache.put(42L, "old windows");
        var invalidation = new PublicCatalogCacheInvalidation(manager);

        TransactionSynchronizationManager.initSynchronization();
        try {
            invalidation.evictStudyRoomWindowsAfterCommit(42L);
            assertThat(cache.get(42L)).isNotNull();
            for (TransactionSynchronization callback :
                    TransactionSynchronizationManager.getSynchronizations()) {
                callback.afterCommit();
            }
            assertThat(cache.get(42L)).isNull();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rolled_back_window_update_leaves_previous_cache_entry_intact() {
        var manager = new ConcurrentMapCacheManager("study-room-windows");
        var cache = manager.getCache("study-room-windows");
        cache.put(42L, "old windows");

        TransactionSynchronizationManager.initSynchronization();
        try {
            new PublicCatalogCacheInvalidation(manager).evictStudyRoomWindowsAfterCommit(42L);
            // A rolled-back transaction has no afterCommit notification.
            assertThat(cache.get(42L).get()).isEqualTo("old windows");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cache_outage_after_commit_does_not_turn_a_committed_write_into_an_error() {
        CacheManager manager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(manager.getCache("study-room-windows")).thenReturn(cache);
        doThrow(new IllegalStateException("redis unavailable")).when(cache).evict(42L);

        TransactionSynchronizationManager.initSynchronization();
        try {
            new PublicCatalogCacheInvalidation(manager).evictStudyRoomWindowsAfterCommit(42L);
            assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
