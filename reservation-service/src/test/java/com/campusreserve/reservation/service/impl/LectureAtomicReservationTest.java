package com.campusreserve.reservation.service.impl;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dao.entity.ReservationOrderEntity;
import com.campusreserve.reservation.dao.entity.ResourceSlotEntity;
import com.campusreserve.reservation.dao.mapper.*;
import com.campusreserve.reservation.domain.ReservationEventType;
import com.campusreserve.reservation.domain.ReservationStatus;
import com.campusreserve.reservation.domain.SlotStatus;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import com.campusreserve.reservation.manager.rate.ReservationAdmissionGuard;
import com.campusreserve.reservation.service.ReservationOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LectureAtomicReservationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private final ResourceSlotMapper slots = mock(ResourceSlotMapper.class);
    private final ReservationOrderMapper orders = mock(ReservationOrderMapper.class);
    private final ReservationWaitlistEntryMapper waiting = mock(ReservationWaitlistEntryMapper.class);
    private final ReservationOutboxService outbox = mock(ReservationOutboxService.class);
    private final ReservationAdmissionGuard admission = mock(ReservationAdmissionGuard.class);
    private final ReservationServiceImpl service = new ReservationServiceImpl(slots,
            mock(ReservableResourceMapper.class), orders, waiting, outbox,
            mock(PublicCatalogCacheInvalidation.class), CLOCK, admission);

    private ReservationOrderEntity existing() {
        var order = new ReservationOrderEntity();
        order.setId(88L);
        order.setSlotId(7L);
        order.setStatus(ReservationStatus.CONFIRMED);
        return order;
    }

    private ResourceSlotEntity openSlot(int available) {
        var slot = new ResourceSlotEntity();
        slot.setId(7L);
        slot.setResourceId(1L);
        slot.setStatus(SlotStatus.OPEN);
        slot.setAvailableCapacity(available);
        slot.setStartsAt(LocalDateTime.of(2026, 10, 25, 14, 0));
        return slot;
    }

    private void stubNewOrder() {
        var saved = new AtomicReference<ReservationOrderEntity>();
        when(orders.insert(any(ReservationOrderEntity.class))).thenAnswer(invocation -> {
            ReservationOrderEntity order = invocation.getArgument(0);
            order.setId(9L);
            order.setCreatedAt(LocalDateTime.of(2026, 10, 24, 0, 0));
            saved.set(order);
            return 1;
        });
        when(orders.selectById(9L)).thenAnswer(invocation -> saved.get());
    }

    @Test void early_replay_does_not_consume_admission_or_capacity() {
        when(orders.selectOne(any())).thenReturn(existing());
        assertThat(service.reserve(7L, "student", "request_123").reservationId()).isEqualTo(88L);
        verifyNoInteractions(slots, admission, waiting, outbox);
    }

    @Test void fresh_replay_after_a_successful_claim_restores_only_its_extra_capacity() {
        when(slots.tryConsumeFutureCapacity(eq(7L), any())).thenReturn(1);
        when(slots.releaseCapacity(7L)).thenReturn(1);
        when(orders.findReplayFresh("student", "request_123")).thenReturn(existing());

        assertThat(service.reserve(7L, "student", "request_123").reservationId()).isEqualTo(88L);
        verify(slots).releaseCapacity(7L);
        verify(slots, never()).lockById(anyLong());
        verify(orders, never()).insert(any(ReservationOrderEntity.class));
        verifyNoInteractions(waiting, outbox);
    }

    @Test void failed_capacity_restoration_is_not_reported_as_a_successful_replay() {
        when(slots.tryConsumeFutureCapacity(eq(7L), any())).thenReturn(1);
        when(orders.findReplayFresh("student", "request_123")).thenReturn(existing());

        assertThatThrownBy(() -> service.reserve(7L, "student", "request_123"))
                .hasMessage("reservation-replay-capacity-release-failed");
        verifyNoInteractions(outbox);
    }

    @Test void cross_slot_unique_key_race_restores_the_claim_before_returning_existing_order() {
        ReservationOrderEntity winner = existing();
        winner.setSlotId(8L);
        when(slots.tryConsumeFutureCapacity(eq(7L), any())).thenReturn(1);
        when(slots.selectById(7L)).thenReturn(openSlot(0));
        when(slots.releaseCapacity(7L)).thenReturn(1);
        when(orders.findReplayFresh("student", "request_123")).thenReturn(null, winner);
        when(orders.insert(any(ReservationOrderEntity.class))).thenThrow(new DuplicateKeyException("key race"));

        assertThat(service.reserve(7L, "student", "request_123").reservationId()).isEqualTo(88L);
        verify(slots).releaseCapacity(7L);
        verifyNoInteractions(waiting, outbox);
    }

    @Test void another_key_for_an_active_booking_throws_so_the_transaction_can_roll_back() {
        when(slots.tryConsumeFutureCapacity(eq(7L), any())).thenReturn(1);
        when(slots.selectById(7L)).thenReturn(openSlot(0));
        when(orders.insert(any(ReservationOrderEntity.class))).thenThrow(new DuplicateKeyException("active race"));

        assertThatThrownBy(() -> service.reserve(7L, "student", "request_123"))
                .isInstanceOf(BusinessException.class).hasMessage("active-reservation-exists");
        // No manual compensation here: the enclosing transaction must undo the claim.
        verify(slots, never()).releaseCapacity(anyLong());
        verifyNoInteractions(outbox);
    }

    @Test void cancellation_releasing_a_seat_before_fallback_lock_can_confirm_the_new_request() {
        when(slots.tryConsumeFutureCapacity(eq(7L), any())).thenReturn(0, 1);
        when(slots.lockById(7L)).thenReturn(openSlot(1));
        stubNewOrder();

        assertThat(service.reserve(7L, "student", "request_123").status()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(slots, times(2)).tryConsumeFutureCapacity(eq(7L), any());
        verify(orders, never()).markConfirmed(anyLong());
        verify(outbox).recordStatusChanged(any(), eq(ReservationEventType.RESERVATION_CONFIRMED));
        verifyNoInteractions(waiting);
    }

    @Test void a_failed_second_claim_under_the_lock_is_an_error_not_a_waitlist_result() {
        when(slots.lockById(7L)).thenReturn(openSlot(1));
        assertThatThrownBy(() -> service.reserve(7L, "student", "request_123"))
                .hasMessage("reservation-capacity-claim-failed");
        verify(orders, never()).insert(any(ReservationOrderEntity.class));
        verifyNoInteractions(waiting, outbox);
    }

    @Test void outbox_failure_propagates_to_the_transaction_manager() {
        when(slots.tryConsumeFutureCapacity(eq(7L), any())).thenReturn(1);
        when(slots.selectById(7L)).thenReturn(openSlot(0));
        stubNewOrder();
        doThrow(new IllegalStateException("outbox-write-failed")).when(outbox).recordStatusChanged(any(), any());
        assertThatThrownBy(() -> service.reserve(7L, "student", "request_123"))
                .hasMessage("outbox-write-failed");
        verify(slots, never()).releaseCapacity(anyLong());
    }
}
