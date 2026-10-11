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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotCancellationServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void cancels_confirmed_and_waitlisted_orders_and_records_one_event_per_student() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservationOrderMapper orderMapper = mock(ReservationOrderMapper.class);
        ReservationWaitlistEntryMapper waitlistMapper = mock(ReservationWaitlistEntryMapper.class);
        SlotCancellationMapper cancellationMapper = mock(SlotCancellationMapper.class);
        ReservationOutboxService outbox = mock(ReservationOutboxService.class);
        PublicCatalogCacheInvalidation cache = mock(PublicCatalogCacheInvalidation.class);
        SlotCancellationServiceImpl service = new SlotCancellationServiceImpl(
                slotMapper, orderMapper, waitlistMapper, cancellationMapper, outbox, cache, CLOCK
        );
        ResourceSlotEntity slot = futureSlot();
        ReservationOrderEntity confirmed = order(11L, ReservationStatus.CONFIRMED);
        ReservationOrderEntity waitlisted = order(12L, ReservationStatus.WAITLISTED);
        AtomicReference<SlotCancellationEntity> audit = new AtomicReference<>();
        when(slotMapper.lockById(7L)).thenReturn(slot);
        when(slotMapper.cancelFutureOpenSlot(7L, LocalDateTime.of(2026, 10, 3, 0, 0))).thenReturn(1);
        when(orderMapper.lockActiveBySlot(7L)).thenReturn(List.of(confirmed, waitlisted));
        when(orderMapper.cancelByAdmin(11L)).thenAnswer(invocation -> {
            confirmed.setStatus(ReservationStatus.CANCELLED_BY_ADMIN);
            return 1;
        });
        when(orderMapper.cancelByAdmin(12L)).thenAnswer(invocation -> {
            waitlisted.setStatus(ReservationStatus.CANCELLED_BY_ADMIN);
            return 1;
        });
        when(waitlistMapper.cancelWaitingByReservation(12L)).thenReturn(1);
        when(orderMapper.selectById(11L)).thenReturn(confirmed);
        when(orderMapper.selectById(12L)).thenReturn(waitlisted);
        when(cancellationMapper.insert(any(SlotCancellationEntity.class))).thenAnswer(invocation -> {
            SlotCancellationEntity record = invocation.getArgument(0);
            record.setCancelledAt(LocalDateTime.of(2026, 10, 3, 0, 1));
            audit.set(record);
            return 1;
        });
        when(cancellationMapper.selectById(7L)).thenAnswer(invocation -> audit.get());

        SlotCancellationResponse response = service.cancelForMaintenance(
                7L, "admin-1", new CancelSlotRequest("  ventilation repair  ")
        );

        assertThat(response.confirmedCount()).isEqualTo(1);
        assertThat(response.waitlistedCount()).isEqualTo(1);
        assertThat(response.reason()).isEqualTo("ventilation repair");
        verify(outbox).recordStatusChanged(confirmed, ReservationEventType.RESERVATION_CANCELLED_BY_ADMIN);
        verify(outbox).recordStatusChanged(waitlisted, ReservationEventType.RESERVATION_CANCELLED_BY_ADMIN);
        verify(cache).evictOpenSlotsAfterCommit(8L);
    }

    @Test
    void rejects_cancellation_after_slot_start_without_writing_events() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ReservationOutboxService outbox = mock(ReservationOutboxService.class);
        SlotCancellationServiceImpl service = new SlotCancellationServiceImpl(
                slotMapper, mock(ReservationOrderMapper.class), mock(ReservationWaitlistEntryMapper.class),
                mock(SlotCancellationMapper.class), outbox, mock(PublicCatalogCacheInvalidation.class), CLOCK
        );
        ResourceSlotEntity slot = futureSlot();
        slot.setStartsAt(LocalDateTime.of(2026, 10, 2, 10, 0));
        when(slotMapper.lockById(7L)).thenReturn(slot);

        assertThatThrownBy(() -> service.cancelForMaintenance(7L, "admin-1", new CancelSlotRequest("repair")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("slot-cancellation-window-closed");
        verify(outbox, never()).recordStatusChanged(any(), any());
    }

    @Test
    void same_operator_and_reason_can_replay_cancellation_without_new_events() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        SlotCancellationMapper cancellationMapper = mock(SlotCancellationMapper.class);
        ReservationOutboxService outbox = mock(ReservationOutboxService.class);
        SlotCancellationServiceImpl service = new SlotCancellationServiceImpl(
                slotMapper, mock(ReservationOrderMapper.class), mock(ReservationWaitlistEntryMapper.class),
                cancellationMapper, outbox, mock(PublicCatalogCacheInvalidation.class), CLOCK
        );
        ResourceSlotEntity slot = futureSlot();
        slot.setStatus(SlotStatus.CANCELLED);
        SlotCancellationEntity audit = new SlotCancellationEntity();
        audit.setSlotId(7L);
        audit.setOperatorReference("admin-1");
        audit.setReason("repair");
        audit.setConfirmedCount(2);
        audit.setWaitlistedCount(1);
        when(slotMapper.lockById(7L)).thenReturn(slot);
        when(cancellationMapper.selectById(7L)).thenReturn(audit);

        assertThat(service.cancelForMaintenance(7L, "admin-1", new CancelSlotRequest("repair"))
                .confirmedCount()).isEqualTo(2);
        verify(outbox, never()).recordStatusChanged(any(), any());
    }

    private static ResourceSlotEntity futureSlot() {
        ResourceSlotEntity slot = new ResourceSlotEntity();
        slot.setId(7L);
        slot.setResourceId(8L);
        slot.setStatus(SlotStatus.OPEN);
        slot.setStartsAt(LocalDateTime.of(2026, 10, 4, 10, 0));
        return slot;
    }

    private static ReservationOrderEntity order(Long id, ReservationStatus status) {
        ReservationOrderEntity order = new ReservationOrderEntity();
        order.setId(id);
        order.setSlotId(7L);
        order.setStatus(status);
        return order;
    }
}
