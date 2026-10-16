package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;
import com.campusreserve.reservation.domain.ReservationEventType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Waits for a broker confirm; a consumer acknowledgement is intentionally not part of this contract. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "campus-reserve.outbox.delivery", name = "enabled", havingValue = "true")
public class RabbitReservationOutboxPublisher implements ReservationOutboxPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${campus-reserve.messaging.exchange:campus.reservation.events}")
    private String exchange;

    @Value("${campus-reserve.lecture-async.exchange:campus.lecture.commands}")
    private String lectureExchange;

    @Value("${campus-reserve.outbox.delivery.confirm-timeout-millis:3000}")
    private long confirmTimeoutMillis;

    @PostConstruct
    void configureMandatoryReturns() {
        rabbitTemplate.setMandatory(true);
    }

    @Override
    public void publish(ReservationOutboxEventEntity event) {
        CorrelationData correlationData = new CorrelationData(event.getEventId());
        rabbitTemplate.convertAndSend(
                event.getEventType() == ReservationEventType.LECTURE_RESERVATION_REQUESTED ? lectureExchange : exchange,
                event.getEventType() == ReservationEventType.LECTURE_RESERVATION_REQUESTED ? "lecture.reserve" :
                        "reservation." + event.getEventType().name().toLowerCase(),
                event.getPayload(),
                message -> {
                    MessageProperties properties = message.getMessageProperties();
                    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                    properties.setHeader("eventId", event.getEventId());
                    properties.setHeader("eventType", event.getEventType().name());
                    properties.setHeader("aggregateType", event.getAggregateType());
                    properties.setHeader("aggregateId", event.getAggregateId().toString());
                    return message;
                },
                correlationData
        );
        awaitBrokerConfirm(correlationData);
    }

    private void awaitBrokerConfirm(CorrelationData correlationData) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlationData.getFuture()
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rabbitmq-confirm-interrupted", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("rabbitmq-confirm-timeout", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("rabbitmq-confirm-failed", exception);
        }
        if (!confirm.isAck()) {
            throw new IllegalStateException("rabbitmq-broker-nack: " + confirm.getReason());
        }
        if (correlationData.getReturned() != null) {
            throw new IllegalStateException("rabbitmq-message-unroutable");
        }
    }
}
