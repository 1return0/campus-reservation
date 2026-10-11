package com.campusreserve.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dto.resp.CancellationResponse;
import com.campusreserve.reservation.dto.resp.ReservationResponse;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import com.campusreserve.reservation.manager.rate.ReservationAdmissionGuard;
import com.campusreserve.reservation.domain.ReservationEventType;
import com.campusreserve.reservation.domain.ReservationStatus;
import com.campusreserve.reservation.domain.SlotStatus;
import com.campusreserve.reservation.domain.WaitlistStatus;
import com.campusreserve.reservation.service.ReservationOutboxService;
import com.campusreserve.reservation.dao.entity.ReservationOrderEntity;
import com.campusreserve.reservation.dao.entity.ReservationWaitlistEntryEntity;
import com.campusreserve.reservation.dao.entity.ReservableResourceEntity;
import com.campusreserve.reservation.dao.entity.ResourceSlotEntity;
import com.campusreserve.reservation.dao.mapper.ReservationOrderMapper;
import com.campusreserve.reservation.dao.mapper.ReservationWaitlistEntryMapper;
import com.campusreserve.reservation.dao.mapper.ResourceSlotMapper;
import com.campusreserve.reservation.dao.mapper.ReservableResourceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ReservationServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void confirms_reservation_when_capacity_is_available() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationWaitlistEntryMapper waitlistMapper = mock(ReservationWaitlistEntryMapper.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                slotMapper, mock(ReservableResourceMapper.class), orderMapper, waitlistMapper, mock(ReservationOutboxService.class),
                mock(PublicCatalogCacheInvalidation.class), CLOCK, mock(ReservationAdmissionGuard.class)
        );
        ResourceSlotEntity slot = openSlot(11L, 1);
        ReservationOrderEntity inserted = new ReservationOrderEntity();

        when(orderMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<ReservationOrderEntity>>any()))
                .thenReturn(null);
        when(slotMapper.lockById(11L)).thenReturn(slot);
        doAnswer(invocation -> {
            ReservationOrderEntity order = invocation.getArgument(0);
            order.setId(101L);
            order.setCreatedAt(LocalDateTime.of(2026, 10, 3, 10, 0));
            copy(order, inserted);
            return 1;
        }).when(orderMapper).insert(ArgumentMatchers.any(ReservationOrderEntity.class));
        when(slotMapper.tryConsumeCapacity(11L)).thenReturn(1);
        doAnswer(invocation -> {
            inserted.setStatus(ReservationStatus.CONFIRMED);
            return 1;
        }).when(orderMapper).markConfirmed(101L);
        when(orderMapper.selectById(101L)).thenReturn(inserted);

        ReservationResponse response = service.reserve(11L, "student-1001", "reserve-1001");

        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(response.slotId()).isEqualTo(11L);
    }

    @Test
    void waitlists_when_capacity_is_exhausted() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationWaitlistEntryMapper waitlistMapper = mock(ReservationWaitlistEntryMapper.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                slotMapper, mock(ReservableResourceMapper.class), orderMapper, waitlistMapper, mock(ReservationOutboxService.class),
                mock(PublicCatalogCacheInvalidation.class), CLOCK, mock(ReservationAdmissionGuard.class)
        );
        ResourceSlotEntity slot = openSlot(12L, 0);
        ReservationOrderEntity inserted = new ReservationOrderEntity();
        AtomicLong sequence = new AtomicLong(201L);

        when(orderMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<ReservationOrderEntity>>any()))
                .thenReturn(null);
        when(slotMapper.lockById(12L)).thenReturn(slot);
        doAnswer(invocation -> {
            ReservationOrderEntity order = invocation.getArgument(0);
            order.setId(sequence.getAndIncrement());
            order.setCreatedAt(LocalDateTime.of(2026, 10, 3, 10, 1));
            copy(order, inserted);
            return 1;
        }).when(orderMapper).insert(ArgumentMatchers.any(ReservationOrderEntity.class));
        when(slotMapper.tryConsumeCapacity(12L)).thenReturn(0);
        doAnswer(invocation -> {
            inserted.setStatus(ReservationStatus.WAITLISTED);
            return 1;
        }).when(orderMapper).markWaitlisted(201L);
        when(orderMapper.selectById(201L)).thenReturn(inserted);
        when(waitlistMapper.insert(ArgumentMatchers.any(ReservationWaitlistEntryEntity.class))).thenReturn(1);

        ReservationResponse response = service.reserve(12L, "student-1002", "reserve-1002");

        assertThat(response.status()).isEqualTo(ReservationStatus.WAITLISTED);
    }

    @Test
    void cancellation_releases_capacity_and_promotes_oldest_waiter() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationWaitlistEntryMapper waitlistMapper = mock(ReservationWaitlistEntryMapper.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                slotMapper, mock(ReservableResourceMapper.class), orderMapper, waitlistMapper, mock(ReservationOutboxService.class),
                mock(PublicCatalogCacheInvalidation.class), CLOCK, mock(ReservationAdmissionGuard.class)
        );
        ReservationOrderEntity confirmed = order(301L, 15L, "student-1001", ReservationStatus.CONFIRMED, "confirmed-no");
        ReservationOrderEntity promoted = order(302L, 15L, "student-1002", ReservationStatus.WAITLISTED, "promoted-no");
        ReservationWaitlistEntryEntity candidate = new ReservationWaitlistEntryEntity();
        candidate.setId(401L);
        candidate.setReservationId(302L);
        candidate.setSlotId(15L);
        candidate.setRequesterReference("student-1002");
        candidate.setStatus(WaitlistStatus.WAITING);

        when(orderMapper.selectById(301L)).thenReturn(confirmed);
        when(orderMapper.lockById(301L)).thenReturn(confirmed);
        when(slotMapper.lockById(15L)).thenReturn(openSlot(15L, 1));
        when(orderMapper.cancelActiveByOwner(301L, "student-1001")).thenReturn(1);
        when(slotMapper.releaseCapacity(15L)).thenReturn(1);
        when(waitlistMapper.lockOldestWaiting(15L)).thenReturn(candidate);
        when(slotMapper.tryConsumeCapacity(15L)).thenReturn(1);
        when(orderMapper.promoteWaitlisted(302L)).thenReturn(1);
        when(waitlistMapper.markPromoted(401L)).thenReturn(1);
        when(orderMapper.selectById(302L)).thenReturn(promoted);

        CancellationResponse response = service.cancel(301L, "student-1001");

        assertThat(response.cancelledReservationId()).isEqualTo(301L);
        assertThat(response.promotedReservationNo()).isEqualTo("promoted-no");
    }

    @Test
    void cancellation_without_a_waiter_evicts_the_changed_slot_cache_after_commit() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationWaitlistEntryMapper waitlistMapper = mock(ReservationWaitlistEntryMapper.class);
        PublicCatalogCacheInvalidation cacheInvalidation = mock(PublicCatalogCacheInvalidation.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                slotMapper, mock(ReservableResourceMapper.class), orderMapper, waitlistMapper,
                mock(ReservationOutboxService.class), cacheInvalidation, CLOCK, mock(ReservationAdmissionGuard.class)
        );
        ReservationOrderEntity confirmed = order(
                303L, 16L, "student-1001", ReservationStatus.CONFIRMED, "cancel-no-waiter"
        );
        ResourceSlotEntity slot = openSlot(16L, 0);
        slot.setResourceId(91L);

        when(orderMapper.selectById(303L)).thenReturn(confirmed);
        when(orderMapper.lockById(303L)).thenReturn(confirmed);
        when(slotMapper.lockById(16L)).thenReturn(slot);
        when(orderMapper.cancelActiveByOwner(303L, "student-1001")).thenReturn(1);
        when(slotMapper.releaseCapacity(16L)).thenReturn(1);
        when(waitlistMapper.lockOldestWaiting(16L)).thenReturn(null);

        CancellationResponse response = service.cancel(303L, "student-1001");

        assertThat(response.promotedReservationNo()).isNull();
        verify(cacheInvalidation).evictOpenSlotsAfterCommit(91L);
    }

    @Test
    void check_in_then_complete_records_distinct_outbox_facts() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationWaitlistEntryMapper waitlistMapper = mock(ReservationWaitlistEntryMapper.class);
        ReservationOutboxService outboxService = mock(ReservationOutboxService.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                slotMapper, mock(ReservableResourceMapper.class), orderMapper, waitlistMapper, outboxService,
                mock(PublicCatalogCacheInvalidation.class), CLOCK, mock(ReservationAdmissionGuard.class)
        );
        ReservationOrderEntity reservation = order(
                501L, 18L, "student-1001", ReservationStatus.CONFIRMED, "fulfilment-no"
        );
        when(orderMapper.checkInConfirmed(501L, "staff-1001")).thenAnswer(invocation -> {
            reservation.setStatus(ReservationStatus.CHECKED_IN);
            reservation.setHandledByReference("staff-1001");
            return 1;
        });
        when(orderMapper.completeCheckedIn(501L, "staff-1001")).thenAnswer(invocation -> {
            reservation.setStatus(ReservationStatus.COMPLETED);
            return 1;
        });
        when(orderMapper.selectById(501L)).thenReturn(reservation);
        when(slotMapper.selectById(18L)).thenReturn(inProgressSlot(18L));

        assertThat(service.checkIn(501L, "staff-1001").status())
                .isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(service.complete(501L, "staff-1001").status())
                .isEqualTo(ReservationStatus.COMPLETED);
        verify(outboxService).recordStatusChanged(reservation, ReservationEventType.RESERVATION_CHECKED_IN);
        verify(outboxService).recordStatusChanged(reservation, ReservationEventType.RESERVATION_COMPLETED);
    }

    @Test
    void repeated_check_in_by_the_same_staff_member_is_a_successful_noop() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationWaitlistEntryMapper waitlistMapper = mock(ReservationWaitlistEntryMapper.class);
        ReservationOutboxService outboxService = mock(ReservationOutboxService.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                slotMapper, mock(ReservableResourceMapper.class), orderMapper, waitlistMapper, outboxService,
                mock(PublicCatalogCacheInvalidation.class), CLOCK, mock(ReservationAdmissionGuard.class)
        );
        ReservationOrderEntity reservation = order(
                601L, 19L, "student-1001", ReservationStatus.CHECKED_IN, "checked-in-no"
        );
        reservation.setHandledByReference("staff-1001");
        when(orderMapper.checkInConfirmed(601L, "staff-1001")).thenReturn(0);
        when(orderMapper.selectById(601L)).thenReturn(reservation);

        assertThat(service.checkIn(601L, "staff-1001").status())
                .isEqualTo(ReservationStatus.CHECKED_IN);
        verify(outboxService, times(0)).recordStatusChanged(
                reservation, ReservationEventType.RESERVATION_CHECKED_IN
        );
    }

    @Test
    void repeated_complete_by_the_same_staff_member_is_a_successful_noop() {
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationOutboxService outboxService = mock(ReservationOutboxService.class);
        ReservationOrderEntity reservation = order(
                602L, 19L, "student-1001", ReservationStatus.COMPLETED, "completed-no"
        );
        reservation.setHandledByReference("staff-1001");
        when(orderMapper.completeCheckedIn(602L, "staff-1001")).thenReturn(0);
        when(orderMapper.selectById(602L)).thenReturn(reservation);
        ReservationServiceImpl service = new ReservationServiceImpl(
                mock(ResourceSlotMapper.class), mock(ReservableResourceMapper.class), orderMapper,
                mock(ReservationWaitlistEntryMapper.class), outboxService,
                mock(PublicCatalogCacheInvalidation.class), CLOCK, mock(ReservationAdmissionGuard.class)
        );

        assertThat(service.complete(602L, "staff-1001").status())
                .isEqualTo(ReservationStatus.COMPLETED);
        verify(outboxService, times(0)).recordStatusChanged(
                reservation, ReservationEventType.RESERVATION_COMPLETED
        );
    }

    @Test
    void repeated_no_show_by_the_same_staff_member_is_a_successful_noop() {
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationOutboxService outboxService = mock(ReservationOutboxService.class);
        ReservationOrderEntity reservation = order(
                603L, 19L, "student-1001", ReservationStatus.NO_SHOW, "no-show-noop"
        );
        reservation.setHandledByReference("staff-1001");
        when(orderMapper.selectById(603L)).thenReturn(reservation);
        ReservationServiceImpl service = new ReservationServiceImpl(
                mock(ResourceSlotMapper.class), mock(ReservableResourceMapper.class), orderMapper,
                mock(ReservationWaitlistEntryMapper.class), outboxService,
                mock(PublicCatalogCacheInvalidation.class), CLOCK, mock(ReservationAdmissionGuard.class)
        );

        assertThat(service.markNoShow(603L, "staff-1001").status())
                .isEqualTo(ReservationStatus.NO_SHOW);
        verify(outboxService, times(0)).recordStatusChanged(
                reservation, ReservationEventType.RESERVATION_NO_SHOW
        );
    }

    @Test
    void rejects_new_booking_after_the_slot_has_started() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                slotMapper, mock(ReservableResourceMapper.class), mock(ReservationOrderMapper.class), mock(ReservationWaitlistEntryMapper.class),
                mock(ReservationOutboxService.class), mock(PublicCatalogCacheInvalidation.class), CLOCK,
                mock(ReservationAdmissionGuard.class)
        );
        ResourceSlotEntity elapsedStart = openSlot(31L, 1);
        elapsedStart.setStartsAt(LocalDateTime.of(2026, 10, 2, 10, 0));
        when(slotMapper.lockById(31L)).thenReturn(elapsedStart);

        assertThatThrownBy(() -> service.reserve(31L, "student-1001", "reserve-closed-window"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("slot-booking-window-closed");
    }

    @Test
    void rejects_check_in_before_the_slot_starts() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                slotMapper, mock(ReservableResourceMapper.class), orderMapper, mock(ReservationWaitlistEntryMapper.class),
                mock(ReservationOutboxService.class), mock(PublicCatalogCacheInvalidation.class), CLOCK,
                mock(ReservationAdmissionGuard.class)
        );
        ReservationOrderEntity reservation = order(41L, 41L, "student-1001", ReservationStatus.CONFIRMED, "future-slot");
        when(orderMapper.selectById(41L)).thenReturn(reservation);
        when(slotMapper.selectById(41L)).thenReturn(openSlot(41L, 1));

        assertThatThrownBy(() -> service.checkIn(41L, "staff-1001"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("reservation-check-in-window-closed");
    }

    @Test
    void marks_no_show_only_after_the_resource_grace_period() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservableResourceMapper resourceMapper = mock(ReservableResourceMapper.class);
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationOutboxService outboxService = mock(ReservationOutboxService.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                slotMapper, resourceMapper, orderMapper, mock(ReservationWaitlistEntryMapper.class), outboxService,
                mock(PublicCatalogCacheInvalidation.class), CLOCK, mock(ReservationAdmissionGuard.class)
        );
        ReservationOrderEntity reservation = order(51L, 51L, "student-1001", ReservationStatus.CONFIRMED, "no-show-no");
        ResourceSlotEntity slot = openSlot(51L, 1);
        slot.setResourceId(501L);
        slot.setStartsAt(LocalDateTime.of(2026, 10, 2, 23, 40));
        slot.setEndsAt(LocalDateTime.of(2026, 10, 3, 1, 0));
        ReservableResourceEntity resource = new ReservableResourceEntity();
        resource.setId(501L);
        resource.setNoShowGraceMinutes(15);
        when(orderMapper.selectById(51L)).thenReturn(reservation);
        when(slotMapper.selectById(51L)).thenReturn(slot);
        when(resourceMapper.selectById(501L)).thenReturn(resource);
        when(orderMapper.markNoShow(51L, "staff-1001")).thenAnswer(invocation -> {
            reservation.setStatus(ReservationStatus.NO_SHOW);
            reservation.setHandledByReference("staff-1001");
            return 1;
        });

        assertThat(service.markNoShow(51L, "staff-1001").status())
                .isEqualTo(ReservationStatus.NO_SHOW);
        verify(outboxService).recordStatusChanged(reservation, ReservationEventType.RESERVATION_NO_SHOW);
    }

    @Test
    void automatic_no_show_marks_only_due_candidates_and_records_one_event() {
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationOutboxService outboxService = mock(ReservationOutboxService.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                mock(ResourceSlotMapper.class), mock(ReservableResourceMapper.class), orderMapper,
                mock(ReservationWaitlistEntryMapper.class), outboxService,
                mock(PublicCatalogCacheInvalidation.class), CLOCK, mock(ReservationAdmissionGuard.class)
        );
        ReservationOrderEntity due = order(71L, 71L, "student-1001", ReservationStatus.CONFIRMED, "automatic-no-show");
        when(orderMapper.lockDueNoShows(ArgumentMatchers.any(), ArgumentMatchers.eq(20)))
                .thenReturn(java.util.List.of(due));
        when(orderMapper.markNoShow(71L, "system-no-show")).thenAnswer(invocation -> {
            due.setStatus(ReservationStatus.NO_SHOW);
            due.setHandledByReference("system-no-show");
            return 1;
        });
        when(orderMapper.selectById(71L)).thenReturn(due);

        assertThat(service.autoMarkNoShows(20)).isEqualTo(1);
        assertThat(due.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        verify(outboxService).recordStatusChanged(due, ReservationEventType.RESERVATION_NO_SHOW);
    }

    @Test
    void rejects_no_show_before_the_resource_grace_period() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservableResourceMapper resourceMapper = mock(ReservableResourceMapper.class);
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationServiceImpl service = new ReservationServiceImpl(
                slotMapper, resourceMapper, orderMapper, mock(ReservationWaitlistEntryMapper.class),
                mock(ReservationOutboxService.class), mock(PublicCatalogCacheInvalidation.class), CLOCK,
                mock(ReservationAdmissionGuard.class)
        );
        ReservationOrderEntity reservation = order(52L, 52L, "student-1001", ReservationStatus.CONFIRMED, "too-early-no-show");
        ResourceSlotEntity slot = openSlot(52L, 1);
        slot.setResourceId(502L);
        slot.setStartsAt(LocalDateTime.of(2026, 10, 3, 0, 0));
        slot.setEndsAt(LocalDateTime.of(2026, 10, 3, 1, 0));
        ReservableResourceEntity resource = new ReservableResourceEntity();
        resource.setId(502L);
        resource.setNoShowGraceMinutes(15);
        when(orderMapper.selectById(52L)).thenReturn(reservation);
        when(slotMapper.selectById(52L)).thenReturn(slot);
        when(resourceMapper.selectById(502L)).thenReturn(resource);

        assertThatThrownBy(() -> service.markNoShow(52L, "staff-1001"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("reservation-no-show-window-not-open");
    }

    private static ResourceSlotEntity openSlot(Long id, int availableCapacity) {
        ResourceSlotEntity slot = new ResourceSlotEntity();
        slot.setId(id);
        slot.setStatus(SlotStatus.OPEN);
        slot.setCapacity(1);
        slot.setAvailableCapacity(availableCapacity);
        slot.setStartsAt(LocalDateTime.of(2026, 10, 4, 10, 0));
        slot.setEndsAt(LocalDateTime.of(2026, 10, 4, 11, 0));
        return slot;
    }

    private static ResourceSlotEntity inProgressSlot(Long id) {
        ResourceSlotEntity slot = openSlot(id, 1);
        slot.setStartsAt(LocalDateTime.of(2026, 10, 2, 10, 0));
        slot.setEndsAt(LocalDateTime.of(2026, 10, 4, 10, 0));
        return slot;
    }

    private static ReservationOrderEntity order(
            Long id,
            Long slotId,
            String requester,
            ReservationStatus status,
            String reservationNo
    ) {
        ReservationOrderEntity result = new ReservationOrderEntity();
        result.setId(id);
        result.setSlotId(slotId);
        result.setRequesterReference(requester);
        result.setStatus(status);
        result.setReservationNo(reservationNo);
        result.setCreatedAt(LocalDateTime.of(2026, 10, 3, 10, 0));
        return result;
    }

    private static void copy(ReservationOrderEntity from, ReservationOrderEntity to) {
        to.setId(from.getId());
        to.setReservationNo(from.getReservationNo());
        to.setSlotId(from.getSlotId());
        to.setRequesterReference(from.getRequesterReference());
        to.setIdempotencyKey(from.getIdempotencyKey());
        to.setStatus(from.getStatus());
        to.setActiveMarker(from.getActiveMarker());
        to.setCreatedAt(from.getCreatedAt());
    }
}
