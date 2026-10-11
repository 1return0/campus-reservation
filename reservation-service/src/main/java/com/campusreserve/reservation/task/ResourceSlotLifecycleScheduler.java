package com.campusreserve.reservation.task;

import com.campusreserve.reservation.dao.mapper.ResourceSlotMapper;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Closes elapsed slots with one idempotent SQL update. It is safe for multiple
 * service instances because each instance applies the same guarded predicate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "campus-reserve.slot-lifecycle", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResourceSlotLifecycleScheduler {

    private final ResourceSlotMapper slotMapper;
    private final PublicCatalogCacheInvalidation catalogCacheInvalidation;
    private final Clock reservationClock;

    @Scheduled(fixedDelayString = "${campus-reserve.slot-lifecycle.fixed-delay-millis:60000}")
    public void closeElapsedSlots() {
        int closedCount = slotMapper.closeExpiredOpenSlots(LocalDateTime.now(reservationClock));
        if (closedCount > 0) {
            catalogCacheInvalidation.evictAllOpenSlotsAfterCommit();
            log.info("Closed {} elapsed resource slots", closedCount);
        }
    }
}
