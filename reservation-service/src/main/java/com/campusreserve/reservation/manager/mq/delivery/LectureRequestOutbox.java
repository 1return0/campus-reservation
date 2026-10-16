package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;
import com.campusreserve.reservation.dao.mapper.ReservationOutboxEventMapper;
import com.campusreserve.reservation.domain.*;
import com.campusreserve.reservation.manager.mq.event.LectureReservationWakeup;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LectureRequestOutbox {
    private final ReservationOutboxEventMapper mapper;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void wake(Long slotId) {
        var event = new ReservationOutboxEventEntity();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType("lecture-request-queue");
        event.setAggregateId(slotId);
        event.setEventType(ReservationEventType.LECTURE_RESERVATION_REQUESTED);
        event.setPublishStatus(OutboxPublishStatus.PENDING);
        event.setPublishAttempts(0);
        try {
            event.setPayload(json.writeValueAsString(new LectureReservationWakeup(1, slotId)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("lecture-wakeup-serialization-failed", exception);
        }
        mapper.insert(event);
    }
}
