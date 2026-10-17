package com.campusreserve.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusreserve.common.api.PageResponse;
import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dto.resp.CancellationResponse;
import com.campusreserve.reservation.dto.resp.ReservationResponse;
import com.campusreserve.reservation.dto.resp.ReservationWorkItemResponse;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import com.campusreserve.reservation.manager.rate.ReservationAdmissionGuard;
import com.campusreserve.reservation.service.ReservationOutboxService;
import com.campusreserve.reservation.service.ReservationService;
import com.campusreserve.reservation.domain.ReservationEventType;
import com.campusreserve.reservation.domain.NoShowPolicy;
import com.campusreserve.reservation.domain.ReservationStatus;
import com.campusreserve.reservation.domain.SlotStatus;
import com.campusreserve.reservation.domain.WaitlistStatus;
import com.campusreserve.reservation.dao.entity.ReservationOrderEntity;
import com.campusreserve.reservation.dao.entity.ReservationWaitlistEntryEntity;
import com.campusreserve.reservation.dao.entity.ReservableResourceEntity;
import com.campusreserve.reservation.dao.entity.ResourceSlotEntity;
import com.campusreserve.reservation.dao.mapper.ReservationOrderMapper;
import com.campusreserve.reservation.dao.mapper.ReservationWaitlistEntryMapper;
import com.campusreserve.reservation.dao.mapper.ResourceSlotMapper;
import com.campusreserve.reservation.dao.mapper.ReservableResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private static final String SYSTEM_NO_SHOW_OPERATOR = "system-no-show";

    private final ResourceSlotMapper resourceSlotMapper;
    private final ReservableResourceMapper resourceMapper;
    private final ReservationOrderMapper reservationOrderMapper;
    private final ReservationWaitlistEntryMapper waitlistEntryMapper;
    private final ReservationOutboxService outboxService;
    private final PublicCatalogCacheInvalidation catalogCacheInvalidation;
    private final Clock reservationClock;
    private final ReservationAdmissionGuard reservationAdmissionGuard;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("hasRole('STUDENT')")
    public ReservationResponse reserve(Long slotId, String requesterReference, String idempotencyKey) {
        ReservationOrderEntity replay = findByIdempotency(requesterReference, idempotencyKey);
        if (replay != null) {
            return toResponse(replay);
        }

        // Retries with the same idempotency key return above without consuming
        // admission quota. New writes can be shed before the capacity update;
        // the replay lookup above has already read MySQL.
        reservationAdmissionGuard.requireAdmission(slotId, requesterReference);

        // The same slot row lock still excludes maintenance and other mutations.
        ResourceSlotEntity slot = resourceSlotMapper.lockById(slotId);
        // READ_COMMITTED plus an uncached re-read observes the winner after waiting for the slot.
        replay = reservationOrderMapper.findReplayFresh(requesterReference, idempotencyKey);
        if (replay != null) { return toResponse(replay); }
        requireBookableOpenSlot(slot);
        ReservationOrderEntity activeReservation = reservationOrderMapper.selectOne(
                new LambdaQueryWrapper<ReservationOrderEntity>()
                        .eq(ReservationOrderEntity::getSlotId, slotId)
                        .eq(ReservationOrderEntity::getRequesterReference, requesterReference)
                        .eq(ReservationOrderEntity::getActiveMarker, 1)
        );
        if (activeReservation != null) {
            throw conflict("active-reservation-exists");
        }

        ReservationOrderEntity reservation = new ReservationOrderEntity();
        reservation.setReservationNo(UUID.randomUUID().toString().replace("-", ""));
        reservation.setSlotId(slot.getId());
        reservation.setRequesterReference(requesterReference);
        reservation.setIdempotencyKey(idempotencyKey);
        reservation.setStatus(ReservationStatus.PENDING);
        reservation.setActiveMarker(1);
        reservation.setVersion(0);
        try {
            reservationOrderMapper.insert(reservation);
        } catch (DuplicateKeyException exception) {
            ReservationOrderEntity existing = reservationOrderMapper.findReplayFresh(requesterReference, idempotencyKey);
            if (existing != null) {
                return toResponse(existing);
            }
            throw conflict("active-reservation-exists");
        }

        if (resourceSlotMapper.tryConsumeCapacity(slotId) == 1) {
            requireChanged(reservationOrderMapper.markConfirmed(reservation.getId()), "reservation-confirm-failed");
        } else {
            requireChanged(reservationOrderMapper.markWaitlisted(reservation.getId()), "reservation-waitlist-failed");
            ReservationWaitlistEntryEntity entry = new ReservationWaitlistEntryEntity();
            entry.setReservationId(reservation.getId());
            entry.setSlotId(slotId);
            entry.setRequesterReference(requesterReference);
            entry.setStatus(WaitlistStatus.WAITING);
            waitlistEntryMapper.insert(entry);
        }
        ReservationOrderEntity completed = requireReservation(reservation.getId());
        outboxService.recordStatusChanged(
                completed,
                completed.getStatus() == ReservationStatus.CONFIRMED
                        ? ReservationEventType.RESERVATION_CONFIRMED
                        : ReservationEventType.RESERVATION_WAITLISTED
        );
        catalogCacheInvalidation.evictOpenSlotsAfterCommit(slot.getResourceId());
        return toResponse(completed);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('STUDENT')")
    public CancellationResponse cancel(Long reservationId, String requesterReference) {
        ReservationOrderEntity reservation = requireReservation(reservationId);
        if (!reservation.getRequesterReference().equals(requesterReference)) {
            throw notFound("reservation-not-found");
        }
        // Use the same slot lock as maintenance cancellation, then refresh the
        // order so neither path can act on a stale pre-cancellation status.
        ResourceSlotEntity slot = resourceSlotMapper.lockById(reservation.getSlotId());
        if (slot == null) {
            throw notFound("slot-not-found");
        }
        reservation = reservationOrderMapper.lockById(reservationId);
        if (reservation == null || !reservation.getRequesterReference().equals(requesterReference)) {
            throw notFound("reservation-not-found");
        }
        if (slot.getStatus() != SlotStatus.OPEN) {
            throw conflict("reservation-cancellation-window-closed");
        }
        ReservationStatus previousStatus = reservation.getStatus();
        if (!slot.getStartsAt().isAfter(now())) {
            throw conflict("reservation-cancellation-window-closed");
        }
        requireChanged(
                reservationOrderMapper.cancelActiveByOwner(reservationId, requesterReference),
                "reservation-state-conflict"
        );

        if (previousStatus == ReservationStatus.WAITLISTED) {
            waitlistEntryMapper.cancelWaitingByReservation(reservationId);
            outboxService.recordStatusChanged(
                    requireReservation(reservationId),
                    ReservationEventType.RESERVATION_CANCELLED
            );
            catalogCacheInvalidation.evictAllOpenSlotsAfterCommit();
            return new CancellationResponse(reservationId, null);
        }
        if (previousStatus != ReservationStatus.CONFIRMED) {
            throw conflict("reservation-state-conflict");
        }

        requireChanged(resourceSlotMapper.releaseCapacity(reservation.getSlotId()), "slot-release-failed");
        outboxService.recordStatusChanged(
                requireReservation(reservationId),
                ReservationEventType.RESERVATION_CANCELLED
        );
        ReservationWaitlistEntryEntity candidate = waitlistEntryMapper.lockOldestWaiting(reservation.getSlotId());
        if (candidate == null) {
            catalogCacheInvalidation.evictOpenSlotsAfterCommit(slot.getResourceId());
            return new CancellationResponse(reservationId, null);
        }

        requireChanged(resourceSlotMapper.tryConsumeCapacity(reservation.getSlotId()), "waitlist-capacity-claim-failed");
        requireChanged(reservationOrderMapper.promoteWaitlisted(candidate.getReservationId()), "waitlist-promotion-failed");
        requireChanged(waitlistEntryMapper.markPromoted(candidate.getId()), "waitlist-entry-promotion-failed");
        ReservationOrderEntity promoted = requireReservation(candidate.getReservationId());
        outboxService.recordStatusChanged(promoted, ReservationEventType.WAITLIST_PROMOTED);
        catalogCacheInvalidation.evictAllOpenSlotsAfterCommit();
        return new CancellationResponse(reservationId, promoted.getReservationNo());
    }

    @Override
    @PreAuthorize("hasRole('STUDENT')")
    public ReservationResponse getById(Long reservationId, String requesterReference) {
        ReservationOrderEntity reservation = requireReservation(reservationId);
        if (!reservation.getRequesterReference().equals(requesterReference)) {
            throw notFound("reservation-not-found");
        }
        return toResponse(reservation);
    }

    @Override
    @PreAuthorize("hasRole('STUDENT')")
    public List<ReservationResponse> listMine(String requesterReference) {
        return reservationOrderMapper.selectList(
                        new LambdaQueryWrapper<ReservationOrderEntity>()
                                .eq(ReservationOrderEntity::getRequesterReference, requesterReference)
                                .orderByDesc(ReservationOrderEntity::getCreatedAt)
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
    public PageResponse<ReservationWorkItemResponse> listWorkQueue(
            ReservationStatus status,
            long page,
            long size
    ) {
        Page<ReservationOrderEntity> result = reservationOrderMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<ReservationOrderEntity>()
                        .eq(status != null, ReservationOrderEntity::getStatus, status)
                        .orderByAsc(ReservationOrderEntity::getStatus)
                        .orderByAsc(ReservationOrderEntity::getCreatedAt)
                        .orderByAsc(ReservationOrderEntity::getId)
        );
        return new PageResponse<>(
                result.getRecords().stream().map(this::toWorkItem).toList(),
                result.getCurrent(),
                result.getSize(),
                result.getTotal()
        );
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
    public ReservationWorkItemResponse checkIn(Long reservationId, String operatorReference) {
        ReservationOrderEntity existing = requireReservation(reservationId);
        if (existing.getStatus() == ReservationStatus.CHECKED_IN
                && operatorReference.equals(existing.getHandledByReference())) {
            return toWorkItem(existing);
        }
        requireCheckInWindow(requireSlot(existing.getSlotId()));
        if (reservationOrderMapper.checkInConfirmed(reservationId, operatorReference) != 1) {
            existing = requireReservation(reservationId);
            if (existing.getStatus() == ReservationStatus.CHECKED_IN
                    && operatorReference.equals(existing.getHandledByReference())) {
                return toWorkItem(existing);
            }
            throw conflict("reservation-check-in-state-conflict");
        }
        ReservationOrderEntity updated = requireReservation(reservationId);
        outboxService.recordStatusChanged(updated, ReservationEventType.RESERVATION_CHECKED_IN);
        return toWorkItem(updated);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
    public ReservationWorkItemResponse complete(Long reservationId, String operatorReference) {
        if (reservationOrderMapper.completeCheckedIn(reservationId, operatorReference) != 1) {
            ReservationOrderEntity existing = requireReservation(reservationId);
            if (existing.getStatus() == ReservationStatus.COMPLETED
                    && operatorReference.equals(existing.getHandledByReference())) {
                return toWorkItem(existing);
            }
            throw conflict("reservation-complete-state-conflict");
        }
        ReservationOrderEntity updated = requireReservation(reservationId);
        outboxService.recordStatusChanged(updated, ReservationEventType.RESERVATION_COMPLETED);
        return toWorkItem(updated);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
    public ReservationWorkItemResponse markNoShow(Long reservationId, String operatorReference) {
        ReservationOrderEntity existing = requireReservation(reservationId);
        if (existing.getStatus() == ReservationStatus.NO_SHOW) {
            return toWorkItem(existing);
        }
        ResourceSlotEntity slot = requireSlot(existing.getSlotId());
        if (now().isBefore(slot.getStartsAt().plusMinutes(requireResource(slot.getResourceId()).getNoShowGraceMinutes()))) {
            throw conflict("reservation-no-show-window-not-open");
        }
        if (reservationOrderMapper.markNoShow(reservationId, operatorReference) != 1) {
            existing = requireReservation(reservationId);
            if (existing.getStatus() == ReservationStatus.NO_SHOW
                    && operatorReference.equals(existing.getHandledByReference())) {
                return toWorkItem(existing);
            }
            throw conflict("reservation-no-show-state-conflict");
        }
        ReservationOrderEntity updated = requireReservation(reservationId);
        outboxService.recordStatusChanged(updated, ReservationEventType.RESERVATION_NO_SHOW);
        return toWorkItem(updated);
    }

    @Override
    @Transactional
    public int autoMarkNoShows(int batchSize) {
        requireAutoBatchSize(batchSize);
        int markedCount = 0;
        for (ReservationOrderEntity reservation : reservationOrderMapper.lockDueNoShows(now(), batchSize)) {
            if (reservationOrderMapper.markNoShow(reservation.getId(), SYSTEM_NO_SHOW_OPERATOR) != 1) {
                continue;
            }
            ReservationOrderEntity updated = requireReservation(reservation.getId());
            outboxService.recordStatusChanged(updated, ReservationEventType.RESERVATION_NO_SHOW);
            markedCount++;
        }
        return markedCount;
    }

    private ResourceSlotEntity requireBookableOpenSlot(ResourceSlotEntity slot) {
        if (slot == null || slot.getStatus() != SlotStatus.OPEN) {
            throw notFound("slot-not-open");
        }
        if (!slot.getStartsAt().isAfter(now())) {
            throw conflict("slot-booking-window-closed");
        }
        return slot;
    }

    private ResourceSlotEntity requireSlot(Long slotId) {
        ResourceSlotEntity slot = resourceSlotMapper.selectById(slotId);
        if (slot == null) {
            throw notFound("slot-not-found");
        }
        return slot;
    }

    private ReservableResourceEntity requireResource(Long resourceId) {
        ReservableResourceEntity resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw notFound("resource-not-found");
        }
        if (resource.getNoShowGraceMinutes() == null
                || resource.getNoShowGraceMinutes() < NoShowPolicy.MIN_GRACE_MINUTES
                || resource.getNoShowGraceMinutes() > NoShowPolicy.MAX_GRACE_MINUTES) {
            throw new IllegalStateException("resource no-show grace policy is invalid");
        }
        return resource;
    }

    private void requireCheckInWindow(ResourceSlotEntity slot) {
        LocalDateTime currentTime = now();
        if (currentTime.isBefore(slot.getStartsAt()) || !currentTime.isBefore(slot.getEndsAt())) {
            throw conflict("reservation-check-in-window-closed");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(reservationClock);
    }

    private ReservationOrderEntity requireReservation(Long reservationId) {
        ReservationOrderEntity reservation = reservationOrderMapper.selectById(reservationId);
        if (reservation == null) {
            throw notFound("reservation-not-found");
        }
        return reservation;
    }

    private ReservationOrderEntity findByIdempotency(String requesterReference, String idempotencyKey) {
        return reservationOrderMapper.selectOne(
                new LambdaQueryWrapper<ReservationOrderEntity>()
                        .eq(ReservationOrderEntity::getRequesterReference, requesterReference)
                        .eq(ReservationOrderEntity::getIdempotencyKey, idempotencyKey)
        );
    }

    private ReservationResponse toResponse(ReservationOrderEntity reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getReservationNo(),
                reservation.getSlotId(),
                reservation.getStatus(),
                reservation.getStatus() == ReservationStatus.WAITLISTED
                        ? waitlistEntryMapper.countWaitingPosition(reservation.getId())
                        : null,
                reservation.getCreatedAt()
        );
    }

    private ReservationWorkItemResponse toWorkItem(ReservationOrderEntity reservation) {
        return new ReservationWorkItemResponse(
                reservation.getId(),
                reservation.getReservationNo(),
                reservation.getSlotId(),
                reservation.getRequesterReference(),
                reservation.getStatus(),
                reservation.getHandledByReference(),
                reservation.getCheckedInAt(),
                reservation.getCompletedAt(),
                reservation.getNoShowAt(),
                reservation.getCreatedAt()
        );
    }

    private static void requireChanged(int affectedRows, String code) {
        if (affectedRows != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, code, code);
        }
    }

    private static void requireAutoBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("no-show batch size must be between 1 and 500");
        }
    }

    private static BusinessException conflict(String code) {
        return new BusinessException(HttpStatus.CONFLICT, code, code);
    }

    private static BusinessException notFound(String code) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, code);
    }
}
