package com.campusreserve.notification.service.impl;

import com.campusreserve.notification.manager.mq.event.IncomingReservationEvent;
import com.campusreserve.notification.service.ReservationNotificationService;
import com.campusreserve.notification.dao.entity.NotificationInboxEventEntity;
import com.campusreserve.notification.dao.entity.StudentNotificationEntity;
import com.campusreserve.notification.dao.mapper.NotificationInboxEventMapper;
import com.campusreserve.notification.dao.mapper.StudentNotificationMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Applies each incoming event at most once to notification-service's own data. */
@Service
@RequiredArgsConstructor
public class ReservationNotificationServiceImpl implements ReservationNotificationService {

    private final NotificationInboxEventMapper inboxEventMapper;
    private final StudentNotificationMapper notificationMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public boolean accept(IncomingReservationEvent event) {
        if (!recordIfFirstSeen(event)) {
            return false;
        }
        StudentNotificationEntity notification = new StudentNotificationEntity();
        notification.setNotificationNo(UUID.randomUUID().toString().replace("-", ""));
        notification.setRecipientReference(event.requesterReference());
        notification.setSourceEventId(event.eventId());
        notification.setNotificationType(event.eventType());
        notification.setTitle(titleFor(event.eventType()));
        notification.setContent("Reservation " + event.reservationNo()
                + " status: " + event.reservationStatus());
        notificationMapper.insert(notification);
        return true;
    }

    private boolean recordIfFirstSeen(IncomingReservationEvent event) {
        NotificationInboxEventEntity inboxEvent = new NotificationInboxEventEntity();
        inboxEvent.setEventId(event.eventId());
        inboxEvent.setEventType(event.eventType());
        inboxEvent.setPayload(writePayload(event));
        try {
            inboxEventMapper.insert(inboxEvent);
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    private String writePayload(IncomingReservationEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("notification-event-serialization-failed", exception);
        }
    }

    private static String titleFor(String eventType) {
        return switch (eventType) {
            case "RESERVATION_CONFIRMED" -> "Reservation confirmed";
            case "RESERVATION_WAITLISTED" -> "Added to waitlist";
            case "WAITLIST_PROMOTED" -> "Waitlist reservation confirmed";
            case "RESERVATION_CANCELLED" -> "Reservation cancelled";
            case "RESERVATION_CANCELLED_BY_ADMIN" -> "Reservation cancelled due to venue unavailability";
            case "RESERVATION_CHECKED_IN" -> "Reservation checked in";
            case "RESERVATION_COMPLETED" -> "Reservation completed";
            case "RESERVATION_NO_SHOW" -> "Reservation marked no-show";
            case "STUDY_ROOM_CONFIRMED" -> "Study room booking confirmed";
            case "STUDY_ROOM_WAITLISTED" -> "Study room booking waitlisted";
            case "STUDY_ROOM_PROMOTED" -> "Study room booking confirmed from waitlist";
            case "STUDY_ROOM_CANCELLED" -> "Study room booking cancelled";
            case "STUDY_ROOM_CANCELLED_BY_ADMIN" -> "Study room booking cancelled for maintenance";
            case "STUDY_ROOM_CHECKED_IN" -> "Study room team checked in";
            case "STUDY_ROOM_COMPLETED" -> "Study room visit completed";
            case "STUDY_ROOM_NO_SHOW" -> "Study room booking marked no-show";
            default -> "Reservation status updated";
        };
    }
}
