package com.campusreserve.reservation.service.impl;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dao.entity.ReservationOrderEntity;
import com.campusreserve.reservation.dao.entity.ResourceSlotEntity;
import com.campusreserve.reservation.dao.entity.SlotCancellationEntity;
import com.campusreserve.reservation.dao.mapper.ReservationOrderMapper;
import com.campusreserve.reservation.dao.mapper.ReservationWaitlistEntryMapper;
import com.campusreserve.reservation.dao.mapper.ResourceSlotMapper;
import com.campusreserve.reservation.dao.mapper.SlotCancellationMapper;
import com.campusreserve.reservation.domain.ReservationEventType;
import com.campusreserve.reservation.domain.ReservationStatus;
import com.campusreserve.reservation.domain.SlotStatus;
import com.campusreserve.reservation.dto.req.CancelSlotRequest;
import com.campusreserve.reservation.dto.resp.SlotCancellationResponse;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import com.campusreserve.reservation.service.ReservationOutboxService;
import com.campusreserve.reservation.service.SlotCancellationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SlotCancellationServiceImpl implements SlotCancellationService {

    private final ResourceSlotMapper slotMapper;
    private final ReservationOrderMapper orderMapper;
    private final ReservationWaitlistEntryMapper waitlistMapper;
    private final SlotCancellationMapper cancellationMapper;
    private final ReservationOutboxService outboxService;
    private final PublicCatalogCacheInvalidation cacheInvalidation;
    private final Clock reservationClock;

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public SlotCancellationResponse cancelForMaintenance(
            Long slotId, String operatorReference, CancelSlotRequest request
    ) {
        if (operatorReference == null || operatorReference.isBlank()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "operator-required", "operator-required");
        }
        if (request == null || request.reason() == null || request.reason().isBlank()
                || request.reason().trim().length() > 300) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "invalid-cancellation-reason", "invalid-cancellation-reason");
        }
        String reason = request.reason().trim();
        // This row is the serialization point for booking, student cancellation
        // and administrator cancellation of the same slot.
        ResourceSlotEntity slot = slotMapper.lockById(slotId);
        if (slot == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "slot-not-found", "slot-not-found");
        }
        if (slot.getStatus() == SlotStatus.CANCELLED) {
            SlotCancellationEntity previous = cancellationMapper.selectById(slotId);
            if (previous != null && operatorReference.equals(previous.getOperatorReference())
                    && reason.equals(previous.getReason())) {
                return toResponse(previous);
            }
            throw conflict("slot-already-cancelled");
        }
        LocalDateTime currentTime = LocalDateTime.now(reservationClock);
        if (slot.getStatus() != SlotStatus.OPEN || !slot.getStartsAt().isAfter(currentTime)) {
            throw conflict("slot-cancellation-window-closed");
        }
        if (slotMapper.cancelFutureOpenSlot(slotId, currentTime) != 1) {
            throw conflict("slot-cancellation-state-conflict");
        }

        List<ReservationOrderEntity> active = orderMapper.lockActiveBySlot(slotId);
        int confirmedCount = 0;
        int waitlistedCount = 0;
        for (ReservationOrderEntity reservation : active) {
            if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
                confirmedCount++;
            } else if (reservation.getStatus() == ReservationStatus.WAITLISTED) {
                if (waitlistMapper.cancelWaitingByReservation(reservation.getId()) != 1) {
                    throw conflict("slot-waitlist-cancellation-conflict");
                }
                waitlistedCount++;
            } else {
                throw conflict("slot-order-cancellation-conflict");
            }
            if (orderMapper.cancelByAdmin(reservation.getId()) != 1) {
                throw conflict("slot-order-cancellation-conflict");
            }
            ReservationOrderEntity cancelled = orderMapper.selectById(reservation.getId());
            outboxService.recordStatusChanged(cancelled, ReservationEventType.RESERVATION_CANCELLED_BY_ADMIN);
        }

        SlotCancellationEntity cancellation = new SlotCancellationEntity();
        cancellation.setSlotId(slotId);
        cancellation.setOperatorReference(operatorReference);
        cancellation.setReason(reason);
        cancellation.setConfirmedCount(confirmedCount);
        cancellation.setWaitlistedCount(waitlistedCount);
        if (cancellationMapper.insert(cancellation) != 1) {
            throw new IllegalStateException("slot-cancellation-audit-write-failed");
        }
        cacheInvalidation.evictOpenSlotsAfterCommit(slot.getResourceId());
        return toResponse(cancellationMapper.selectById(slotId));
    }

    @Override
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
    public SlotCancellationResponse getCancellation(Long slotId) {
        SlotCancellationEntity cancellation = cancellationMapper.selectById(slotId);
        if (cancellation == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "slot-cancellation-not-found", "slot-cancellation-not-found");
        }
        return toResponse(cancellation);
    }

    private static SlotCancellationResponse toResponse(SlotCancellationEntity entity) {
        if (entity == null) {
            throw new IllegalStateException("slot-cancellation-audit-read-failed");
        }
        return new SlotCancellationResponse(entity.getSlotId(), entity.getOperatorReference(),
                entity.getReason(), entity.getConfirmedCount(), entity.getWaitlistedCount(),
                entity.getCancelledAt());
    }

    private static BusinessException conflict(String code) {
        return new BusinessException(HttpStatus.CONFLICT, code, code);
    }
}
