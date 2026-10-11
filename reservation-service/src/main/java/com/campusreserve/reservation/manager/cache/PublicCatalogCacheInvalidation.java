package com.campusreserve.reservation.manager.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Invalidates only after the database transaction commits successfully. */
@Component
@RequiredArgsConstructor
@Slf4j
public class PublicCatalogCacheInvalidation {

    private final CacheManager cacheManager;

    public void evictPublishedResourcesAfterCommit() {
        afterCommit(() -> evictAll("published-resources"));
    }

    public void evictOpenSlotsAfterCommit(Long resourceId) {
        afterCommit(() -> {
            Cache cache = cacheManager.getCache("open-slots");
            if (cache != null) {
                cache.evict(resourceId);
            }
        });
    }

    public void evictAllOpenSlotsAfterCommit() {
        afterCommit(() -> evictAll("open-slots"));
    }

    public void evictStudyRoomWindowsAfterCommit(Long resourceId) {
        afterCommit(() -> {
            Cache cache = cacheManager.getCache("study-room-windows");
            if (cache != null) {
                cache.evict(resourceId);
            }
        });
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(action);
            }
        });
    }

    private void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            // MySQL has committed. An optional cache outage must not make the
            // caller believe the write failed; the short TTL bounds staleness.
            log.warn("Optional public catalog cache invalidation failed", exception);
        }
    }

    private void evictAll(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }
}
