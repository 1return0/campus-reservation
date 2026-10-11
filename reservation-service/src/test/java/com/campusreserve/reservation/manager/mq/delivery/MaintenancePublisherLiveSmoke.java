package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;
import com.campusreserve.reservation.domain.ReservationEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in actual publisher-confirm/routing smoke, using an isolated auto-delete topology. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_RABBIT_LIVE_SMOKE", matches = "true")
class MaintenancePublisherLiveSmoke {

    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String exchange = "campus.smoke.publisher." + suffix;
    private final String queue = "campus.smoke.publisher.queue." + suffix;
    private CachingConnectionFactory connectionFactory;
    private RabbitAdmin admin;
    private RabbitTemplate template;

    @BeforeEach
    void openRabbitConnection() {
        connectionFactory = new CachingConnectionFactory(System.getenv("RABBITMQ_HOST"));
        connectionFactory.setPort(Integer.parseInt(System.getenv("RABBITMQ_PORT")));
        connectionFactory.setUsername(System.getenv("RABBITMQ_USERNAME"));
        connectionFactory.setPassword(System.getenv("RABBITMQ_PASSWORD"));
        connectionFactory.setVirtualHost(System.getenv("RABBITMQ_VIRTUAL_HOST"));
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        template = new RabbitTemplate(connectionFactory);
        admin = new RabbitAdmin(connectionFactory);
        TopicExchange topic = new TopicExchange(exchange, false, true);
        Queue testQueue = new Queue(queue, false, false, true);
        admin.declareExchange(topic);
        admin.declareQueue(testQueue);
        admin.declareBinding(BindingBuilder.bind(testQueue).to(topic).with("reservation.#"));
    }

    @Test
    void maintenance_event_reaches_the_test_queue_after_broker_confirm() {
        String eventId = UUID.randomUUID().toString();
        ReservationOutboxEventEntity event = new ReservationOutboxEventEntity();
        event.setEventId(eventId);
        event.setEventType(ReservationEventType.RESERVATION_CANCELLED_BY_ADMIN);
        event.setAggregateType("reservation");
        event.setAggregateId(991L);
        event.setPayload("{\"eventId\":\"" + eventId + "\"}");
        RabbitReservationOutboxPublisher publisher = new RabbitReservationOutboxPublisher(template);
        ReflectionTestUtils.setField(publisher, "exchange", exchange);
        ReflectionTestUtils.setField(publisher, "confirmTimeoutMillis", 5000L);
        publisher.configureMandatoryReturns();

        publisher.publish(event);

        Message received = template.receive(queue, 5000);
        assertThat(received).isNotNull();
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).contains(eventId);
        assertThat(received.getMessageProperties().getHeaders().get("eventId")).isEqualTo(eventId);
    }

    @AfterEach
    void removeOnlyTheTemporaryTopology() {
        if (admin != null) {
            admin.deleteQueue(queue);
            admin.deleteExchange(exchange);
        }
        if (connectionFactory != null) connectionFactory.destroy();
    }
}
