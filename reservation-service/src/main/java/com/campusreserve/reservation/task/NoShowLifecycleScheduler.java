package com.campusreserve.reservation.task;

import com.campusreserve.reservation.service.ReservationService;
import com.campusreserve.reservation.service.StudyRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Applies a system no-show only after a resource's grace period has elapsed.
 * The services retain the transactional state change and Outbox ownership.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "campus-reserve.no-show-lifecycle", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class NoShowLifecycleScheduler {

    private final ReservationService reservationService;
    private final StudyRoomService studyRoomService;

    @Value("${campus-reserve.no-show-lifecycle.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${campus-reserve.no-show-lifecycle.fixed-delay-millis:60000}")
    public void markDueNoShows() {
        int fixedSlotCount = reservationService.autoMarkNoShows(batchSize);
        int studyRoomCount = studyRoomService.autoMarkNoShows(batchSize);
        if (fixedSlotCount + studyRoomCount > 0) {
            log.info("Automatically marked {} fixed-slot and {} study-room no-shows",
                    fixedSlotCount, studyRoomCount);
        }
    }
}
