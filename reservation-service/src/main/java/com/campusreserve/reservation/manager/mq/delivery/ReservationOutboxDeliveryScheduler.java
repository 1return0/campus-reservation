package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Trigger only: leasing and message delivery remain in dedicated components. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "campus-reserve.outbox.delivery", name = "enabled", havingValue = "true")
public class ReservationOutboxDeliveryScheduler {

    private final ReservationOutboxLeaseService leaseService;
    private final ReservationOutboxPublisher publisher;

    @Value("${campus-reserve.outbox.delivery.batch-size:20}")
    private int batchSize;

    @Value("${campus-reserve.outbox.delivery.lease-seconds:30}")
    private long leaseSeconds;

    @Scheduled(fixedDelayString = "${campus-reserve.outbox.delivery.fixed-delay-millis:1000}")
    public void deliverPendingEvents() {
        for (ReservationOutboxEventEntity candidate : leaseService.findCandidates(batchSize)) {
            deliverCandidate(candidate);
        }
    }

    private void deliverCandidate(ReservationOutboxEventEntity candidate) {
        String leaseToken = leaseService.acquireLease(candidate, Duration.ofSeconds(leaseSeconds));
        if (leaseToken == null) {
            return;
        }
        try {
            publisher.publish(candidate);
            leaseService.markPublished(candidate.getId(), leaseToken);
        } catch (RuntimeException exception) {
            leaseService.releaseAfterFailure(candidate.getId(), leaseToken, exception.getMessage());
        }
    }
}
