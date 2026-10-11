package com.campusreserve.notification.service.impl;

import com.campusreserve.notification.manager.mq.event.IncomingReservationEvent;
import com.campusreserve.notification.dao.entity.NotificationInboxEventEntity;
import com.campusreserve.notification.dao.entity.StudentNotificationEntity;
import com.campusreserve.notification.dao.mapper.NotificationInboxEventMapper;
import com.campusreserve.notification.dao.mapper.StudentNotificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ReservationNotificationServiceImplTest {

    @Test
    void records_inbox_then_creates_notification_for_first_delivery() {
        NotificationInboxEventMapper inboxMapper = mock(NotificationInboxEventMapper.class);
        StudentNotificationMapper notificationMapper = mock(StudentNotificationMapper.class);
        ReservationNotificationServiceImpl service = new ReservationNotificationServiceImpl(
                inboxMapper, notificationMapper, new ObjectMapper().findAndRegisterModules()
        );
        when(inboxMapper.insert(any(NotificationInboxEventEntity.class))).thenReturn(1);
        when(notificationMapper.insert(any(StudentNotificationEntity.class))).thenReturn(1);

        boolean created = service.accept(event("event-001"));

        assertThat(created).isTrue();
        verify(inboxMapper).insert(any(NotificationInboxEventEntity.class));
        verify(notificationMapper).insert(any(StudentNotificationEntity.class));
    }

    @Test
    void duplicate_delivery_is_a_successful_noop() {
        NotificationInboxEventMapper inboxMapper = mock(NotificationInboxEventMapper.class);
        StudentNotificationMapper notificationMapper = mock(StudentNotificationMapper.class);
        ReservationNotificationServiceImpl service = new ReservationNotificationServiceImpl(
                inboxMapper, notificationMapper, new ObjectMapper().findAndRegisterModules()
        );
        when(inboxMapper.insert(any(NotificationInboxEventEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate event"));

        boolean created = service.accept(event("event-001"));

        assertThat(created).isFalse();
        verify(notificationMapper, never()).insert(any(StudentNotificationEntity.class));
    }

    @Test
    void maintenance_cancellation_creates_student_visible_notification() {
        NotificationInboxEventMapper inboxMapper = mock(NotificationInboxEventMapper.class);
        StudentNotificationMapper notificationMapper = mock(StudentNotificationMapper.class);
        ReservationNotificationServiceImpl service = new ReservationNotificationServiceImpl(
                inboxMapper, notificationMapper, new ObjectMapper().findAndRegisterModules()
        );
        when(inboxMapper.insert(any(NotificationInboxEventEntity.class))).thenReturn(1);
        when(notificationMapper.insert(any(StudentNotificationEntity.class))).thenReturn(1);
        IncomingReservationEvent cancelled = new IncomingReservationEvent(
                "event-maintenance-001", 1, "RESERVATION_CANCELLED_BY_ADMIN", 101L,
                "reservation-001", 11L, "student-1001", "CANCELLED_BY_ADMIN",
                Instant.parse("2026-10-03T08:00:00Z")
        );

        assertThat(service.accept(cancelled)).isTrue();
        ArgumentCaptor<StudentNotificationEntity> captured = ArgumentCaptor.forClass(StudentNotificationEntity.class);
        verify(notificationMapper).insert(captured.capture());
        assertThat(captured.getValue().getRecipientReference()).isEqualTo("student-1001");
        assertThat(captured.getValue().getTitle()).contains("venue unavailability");
    }

    private static IncomingReservationEvent event(String eventId) {
        return new IncomingReservationEvent(
                eventId,
                1,
                "RESERVATION_CONFIRMED",
                101L,
                "reservation-001",
                11L,
                "student-1001",
                "CONFIRMED",
                Instant.parse("2026-10-03T08:00:00Z")
        );
    }
}
