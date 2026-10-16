package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dao.entity.LectureReservationRequestEntity;
import com.campusreserve.reservation.dao.mapper.*;
import com.campusreserve.reservation.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LectureRequestPersistence {
    private final LectureReservationRequestMapper requests;
    private final ResourceSlotMapper slots;
    private final ReservableResourceMapper resources;
    private final LectureRequestOutbox outbox;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LectureReservationRequestEntity accept(Long slotId, String owner, String key) {
        // This row is NOT resource_slot. Its lock lasts only through admission persistence.
        // A sequence number cannot be allocated until the previous allocation commits/rolls back.
        requests.lockSequence(slotId);
        var replay = requests.replay(owner, key);
        if (replay != null) return requireSameSlot(replay, slotId);
        var slot = slots.selectById(slotId); // no reservation/maintenance lock in HTTP
        if (slot == null) throw new BusinessException(HttpStatus.NOT_FOUND, "slot-not-found", "slot-not-found");
        var resource = resources.selectById(slot.getResourceId());
        if (resource == null || !"LECTURE".equals(resource.getResourceType()))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "lecture-slot-required", "lecture-slot-required");
        if (resource.getStatus() != ResourceStatus.PUBLISHED || slot.getStatus() != SlotStatus.OPEN
                || !slot.getStartsAt().isAfter(LocalDateTime.now(clock)))
            throw new BusinessException(HttpStatus.CONFLICT, "slot-not-bookable", "slot-not-bookable");
        requests.incrementSequence(slotId);
        var request = new LectureReservationRequestEntity();
        request.setRequestId(UUID.randomUUID().toString());
        request.setSlotId(slotId);
        request.setSequenceNo(requests.sequence(slotId));
        request.setRequesterReference(owner);
        request.setIdempotencyKey(key);
        request.setStatus("PROCESSING");
        request.setAttempts(0);
        requests.insert(request);
        outbox.wake(slotId);
        return request; // proxy commits BOTH rows before returning to the API service
    }

    public static LectureReservationRequestEntity requireSameSlot(LectureReservationRequestEntity request, Long slotId) {
        if (!request.getSlotId().equals(slotId))
            throw new BusinessException(HttpStatus.CONFLICT, "idempotency-key-slot-mismatch", "idempotency-key-slot-mismatch");
        return request;
    }
}
