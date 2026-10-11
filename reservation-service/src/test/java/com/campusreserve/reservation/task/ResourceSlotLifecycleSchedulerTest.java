package com.campusreserve.reservation.task;

import com.campusreserve.reservation.dao.mapper.ResourceSlotMapper;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceSlotLifecycleSchedulerTest {

    @Test
    void evicts_open_slot_cache_only_when_elapsed_slots_were_closed() {
        ResourceSlotMapper mapper = mock(ResourceSlotMapper.class);
        PublicCatalogCacheInvalidation cacheInvalidation = mock(PublicCatalogCacheInvalidation.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);
        ResourceSlotLifecycleScheduler scheduler = new ResourceSlotLifecycleScheduler(mapper, cacheInvalidation, clock);

        when(mapper.closeExpiredOpenSlots(LocalDateTime.of(2026, 10, 3, 0, 0))).thenReturn(2);
        scheduler.closeElapsedSlots();
        verify(cacheInvalidation).evictAllOpenSlotsAfterCommit();

        when(mapper.closeExpiredOpenSlots(LocalDateTime.of(2026, 10, 3, 0, 0))).thenReturn(0);
        scheduler.closeElapsedSlots();
        verify(cacheInvalidation).evictAllOpenSlotsAfterCommit();
        verify(cacheInvalidation, never()).evictPublishedResourcesAfterCommit();
    }
}
