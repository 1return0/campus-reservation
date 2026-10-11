package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;
import com.campusreserve.reservation.dao.mapper.ReservationOutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Persists lease state in a short transaction before network publication begins. */
@Service
@RequiredArgsConstructor
public class ReservationOutboxLeaseService {

    private static final ZoneId DATABASE_TIME_ZONE = ZoneId.of("Asia/Shanghai");

    private final ReservationOutboxEventMapper outboxEventMapper;

    public List<ReservationOutboxEventEntity> findCandidates(int batchSize) {
        return outboxEventMapper.findPublishableCandidates(batchSize);
    }

    @Transactional
    public String acquireLease(ReservationOutboxEventEntity event, Duration leaseDuration) {
        String leaseToken = UUID.randomUUID().toString();
        int updated = outboxEventMapper.acquireLease(
                event.getId(),
                leaseToken,
                LocalDateTime.now(DATABASE_TIME_ZONE).plus(leaseDuration)
        );
        return updated == 1 ? leaseToken : null;
    }

    @Transactional
    public void markPublished(Long eventId, String leaseToken) {
        if (outboxEventMapper.markPublished(eventId, leaseToken) != 1) {
            throw new IllegalStateException("outbox-mark-published-conflict");
        }
    }

    @Transactional
    public void releaseAfterFailure(Long eventId, String leaseToken, String error) {
        outboxEventMapper.releaseAfterFailure(eventId, leaseToken, sanitizeError(error));
    }

    private static String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return "outbox-publisher-failed";
        }
        return error.substring(0, Math.min(error.length(), 512));
    }
}
