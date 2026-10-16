package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.reservation.dao.mapper.LectureReservationRequestMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Re-wake durable unfinished requests, including requests whose original signal reached a DLQ. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "campus-reserve.outbox.delivery", name = "enabled", havingValue = "true")
public class LectureRequestRecoveryScheduler {
    private final LectureReservationRequestMapper requests;
    private final LectureRequestOutbox outbox;
    private final TransactionTemplate transaction;

    public LectureRequestRecoveryScheduler(LectureReservationRequestMapper requests, LectureRequestOutbox outbox,
            PlatformTransactionManager manager) {
        this.requests = requests;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${campus-reserve.lecture-async.recovery-delay-millis:5000}")
    public void recover() {
        try {
            for (Long slot : requests.recoverableSlots()) {
                transaction.executeWithoutResult(ignored -> {
                    if (requests.claimWakeup(slot) > 0) outbox.wake(slot);
                });
            }
        } catch (RuntimeException unavailable) {
            log.warn("lecture-request-recovery-unavailable type={}", unavailable.getClass().getSimpleName());
        }
    }
}
