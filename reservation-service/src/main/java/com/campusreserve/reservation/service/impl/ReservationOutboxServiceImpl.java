package com.campusreserve.reservation.service.impl;

import com.campusreserve.reservation.domain.OutboxPublishStatus;
import com.campusreserve.reservation.domain.ReservationEventType;
import com.campusreserve.reservation.dao.entity.ReservationOrderEntity;
import com.campusreserve.reservation.dao.entity.StudyRoomBookingEntity;
import com.campusreserve.reservation.dao.entity.StudyRoomBookingMemberEntity;
import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;
import com.campusreserve.reservation.dao.mapper.ReservationOutboxEventMapper;
import com.campusreserve.reservation.service.ReservationOutboxService;
import com.campusreserve.reservation.manager.mq.event.ReservationStatusChangedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.campusreserve.reservation.domain.ReservationStatus;

/** Writes a publishable fact in the same transaction as its reservation change. */
@Service
@RequiredArgsConstructor
public class ReservationOutboxServiceImpl implements ReservationOutboxService {

    private final ReservationOutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void recordStatusChanged(
            ReservationOrderEntity reservation,
            ReservationEventType eventType
    ) {
        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        ReservationStatusChangedEvent event = new ReservationStatusChangedEvent(
                eventId,
                1,
                eventType,
                reservation.getId(),
                reservation.getReservationNo(),
                reservation.getSlotId(),
                reservation.getRequesterReference(),
                reservation.getStatus(),
                occurredAt
        );
        ReservationOutboxEventEntity outboxEvent = new ReservationOutboxEventEntity();
        outboxEvent.setEventId(eventId);
        outboxEvent.setAggregateType("reservation");
        outboxEvent.setAggregateId(reservation.getId());
        outboxEvent.setEventType(eventType);
        outboxEvent.setPayload(writePayload(event));
        outboxEvent.setPublishStatus(OutboxPublishStatus.PENDING);
        outboxEvent.setPublishAttempts(0);
        outboxEventMapper.insert(outboxEvent);
    }

    @Override
    public void recordStudyRoomStatusChanged(StudyRoomBookingEntity booking,
                                             List<StudyRoomBookingMemberEntity> participants,
                                             ReservationEventType eventType) {
        ReservationStatus status = ReservationStatus.valueOf(booking.getStatus().name());
        for (StudyRoomBookingMemberEntity participant : participants) {
            String eventId = UUID.randomUUID().toString();
            ReservationStatusChangedEvent event = new ReservationStatusChangedEvent(
                    eventId, 1, eventType, booking.getId(), booking.getBookingNo(), null,
                    participant.getUserReference(), status, Instant.now());
            ReservationOutboxEventEntity row = new ReservationOutboxEventEntity();
            row.setEventId(eventId);
            row.setAggregateType("study-room-booking");
            row.setAggregateId(booking.getId());
            row.setEventType(eventType);
            row.setPayload(writePayload(event));
            row.setPublishStatus(OutboxPublishStatus.PENDING);
            row.setPublishAttempts(0);
            outboxEventMapper.insert(row);
        }
    }

    private String writePayload(ReservationStatusChangedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("reservation-event-serialization-failed", exception);
        }
    }
}
