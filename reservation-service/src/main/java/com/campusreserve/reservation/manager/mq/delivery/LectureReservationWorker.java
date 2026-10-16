package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dao.entity.LectureReservationRequestEntity;
import com.campusreserve.reservation.dao.mapper.*;
import com.campusreserve.reservation.service.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** MQ is a wake-up signal. The committed database queue, NOT message arrival, decides FIFO. */
@Slf4j
@Component
public class LectureReservationWorker {
    private final LectureReservationRequestMapper requests;
    private final ResourceSlotMapper slots;
    private final ReservationService reservations;
    private final TransactionTemplate transaction;

    public LectureReservationWorker(LectureReservationRequestMapper requests, ResourceSlotMapper slots,
            ReservationService reservations, PlatformTransactionManager manager) {
        this.requests = requests;
        this.slots = slots;
        this.reservations = reservations;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(15);
    }

    public void drain(Long slotId) {
        // Bounded work per delivery; every request also has its own durable wake-up.
        for (int i = 0; i < 20 && processOne(slotId); i++) { }
    }

    public boolean processOne(Long slotId) {
        AtomicReference<LectureReservationRequestEntity> selected = new AtomicReference<>();
        var previous = SecurityContextHolder.getContext();
        var worker = SecurityContextHolder.createEmptyContext();
        worker.setAuthentication(new UsernamePasswordAuthenticationToken("lecture-queue-worker", null,
                List.of(new SimpleGrantedAuthority("LECTURE_QUEUE_WORKER"))));
        SecurityContextHolder.setContext(worker);
        try {
            return Boolean.TRUE.equals(transaction.execute(ignored -> {
                slots.lockById(slotId); // same maintenance/cancellation mutex as the original transaction
                var head = requests.head(slotId);
                // Both timestamps use the DB clock, regardless of JVM/session time-zone differences.
                if (head == null || !Boolean.TRUE.equals(head.getReady())) return false;
                selected.set(head);
                var result = reservations.reserveAccepted(slotId, head.getRequesterReference(), head.getIdempotencyKey());
                if (!result.slotId().equals(slotId)) throw new BusinessException(
                        org.springframework.http.HttpStatus.CONFLICT, "idempotency-key-slot-mismatch", "idempotency-key-slot-mismatch");
                if (result.status() != com.campusreserve.reservation.domain.ReservationStatus.CONFIRMED
                        && result.status() != com.campusreserve.reservation.domain.ReservationStatus.WAITLISTED)
                    throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT,
                            "reservation-already-finalized", "reservation-already-finalized");
                requireChanged(requests.finish(head.getId(), result.status().name(), result.reservationId(), null));
                // Reservation, capacity, waitlist, notification Outbox AND final result commit together.
                return true;
            }));
        } catch (RuntimeException failure) {
            // The ENTIRE attempt has rolled back before finalizing/defering in a separate transaction.
            var head = selected.get();
            if (head == null) {
                log.warn("lecture-worker-unavailable slotId={} type={}", slotId, failure.getClass().getSimpleName());
                return false;
            }
            boolean permanent = failure instanceof BusinessException;
            try {
                transaction.executeWithoutResult(ignored -> {
                    slots.lockById(slotId);
                    var current = requests.head(slotId);
                    if (current == null || !current.getId().equals(head.getId())) return;
                    if (permanent) {
                        requireChanged(requests.finish(head.getId(), "FAILED", null, ((BusinessException) failure).getCode()));
                    } else {
                        requests.defer(head.getId(), Math.min(60, 1 << Math.min(6, head.getAttempts())));
                    }
                });
            } catch (RuntimeException unavailable) {
                // If DB itself is unavailable the committed PROCESSING row remains recoverable.
                log.warn("lecture-worker-recovery-deferred slotId={} type={}", slotId, unavailable.getClass().getSimpleName());
                return false;
            }
            log.warn("lecture-worker-attempt-failed requestId={} terminal={} type={}",
                    head.getRequestId(), permanent, failure.getClass().getSimpleName());
            return permanent;
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static void requireChanged(int count) {
        if (count != 1) throw new IllegalStateException("lecture-request-state-conflict");
    }
}
