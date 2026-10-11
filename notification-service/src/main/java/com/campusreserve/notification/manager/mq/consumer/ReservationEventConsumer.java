package com.campusreserve.notification.manager.mq.consumer;

import com.campusreserve.notification.service.ReservationNotificationService;
import com.campusreserve.notification.manager.mq.event.IncomingReservationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "campus-reserve.messaging.consumer",
        name = "enabled",
        havingValue = "true"
)
public class ReservationEventConsumer {

    private final ReservationNotificationService notificationService;
    private final ObjectMapper objectMapper;

    @RabbitListener(
            queues = "${campus-reserve.messaging.queue:campus.notification.reservation.v1}",
            containerFactory = "reservationEventRabbitListenerContainerFactory"
    )
    public void consume(Message message) {
        try {
            IncomingReservationEvent event = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8),
                    IncomingReservationEvent.class
            );
            validate(event, message.getMessageProperties().getHeaders());
            notificationService.accept(event);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // Invalid data cannot become valid after a retry; send it to the DLQ.
            throw new AmqpRejectAndDontRequeueException(
                    "reservation event violates notification consumer contract",
                    exception
            );
        }
    }

    private static void validate(IncomingReservationEvent event, Map<String, Object> headers) {
        if (event.eventId() == null || event.eventId().isBlank()
                || event.eventType() == null || event.eventType().isBlank()
                || event.requesterReference() == null || event.requesterReference().isBlank()
                || event.reservationNo() == null || event.reservationNo().isBlank()) {
            throw new IllegalArgumentException("reservation event has required blank fields");
        }
        Object headerEventId = headers.get("eventId");
        if (headerEventId == null || !event.eventId().equals(headerEventId.toString())) {
            throw new IllegalArgumentException("eventId header does not match event payload");
        }
    }
}
