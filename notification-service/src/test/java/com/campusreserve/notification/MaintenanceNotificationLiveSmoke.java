package com.campusreserve.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real Broker -> @RabbitListener -> Inbox/notification smoke, fully isolated by topology name. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_RABBIT_LIVE_SMOKE", matches = "true")
@SpringBootTest(classes = NotificationServiceApplication.class, properties = {
        "NACOS_DISCOVERY_ENABLED=false",
        "RESERVATION_EVENT_CONSUMER_ENABLED=true",
        "spring.rabbitmq.listener.simple.retry.enabled=false"
})
class MaintenanceNotificationLiveSmoke {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String EXCHANGE = "campus.smoke." + SUFFIX;
    private static final String QUEUE = "campus.smoke.notification." + SUFFIX;
    private static final String DLX = "campus.smoke.dlx." + SUFFIX;
    private static final String DLQ = "campus.smoke.dlq." + SUFFIX;

    @DynamicPropertySource
    static void isolateRabbitTopology(DynamicPropertyRegistry registry) {
        registry.add("campus-reserve.messaging.exchange", () -> EXCHANGE);
        registry.add("campus-reserve.messaging.queue", () -> QUEUE);
        registry.add("campus-reserve.messaging.dead-letter-exchange", () -> DLX);
        registry.add("campus-reserve.messaging.dead-letter-queue", () -> DLQ);
    }

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private String eventId;

    @Test
    void new_maintenance_event_is_consumed_and_persisted_once() throws Exception {
        eventId = UUID.randomUUID().toString();
        String recipient = "smoke-" + SUFFIX.substring(0, 16);
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "schemaVersion", 1,
                "eventType", "RESERVATION_CANCELLED_BY_ADMIN",
                "reservationId", 991L,
                "reservationNo", SUFFIX,
                "slotId", 992L,
                "requesterReference", recipient,
                "reservationStatus", "CANCELLED_BY_ADMIN",
                "occurredAt", Instant.now().toString()
        ));
        rabbitTemplate.convertAndSend(EXCHANGE, "reservation.reservation_cancelled_by_admin", payload,
                message -> {
                    message.getMessageProperties().setContentType(MessageProperties.CONTENT_TYPE_JSON);
                    message.getMessageProperties().setHeader("eventId", eventId);
                    return message;
                });

        long deadline = System.currentTimeMillis() + 15000;
        int notifications = 0;
        while (System.currentTimeMillis() < deadline) {
            notifications = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM student_notification WHERE source_event_id = ?", Integer.class, eventId
            );
            if (notifications == 1) break;
            Thread.sleep(200);
        }
        assertThat(notifications).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT title FROM student_notification WHERE source_event_id = ?", String.class, eventId
        )).contains("venue unavailability");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_inbox_event WHERE event_id = ?", Integer.class, eventId
        )).isEqualTo(1);
    }

    @AfterEach
    void removeOnlyThisSmokesDataAndTopology() {
        listenerRegistry.stop();
        if (eventId != null) {
            jdbc.update("DELETE FROM student_notification WHERE source_event_id = ?", eventId);
            jdbc.update("DELETE FROM notification_inbox_event WHERE event_id = ?", eventId);
        }
        amqpAdmin.deleteQueue(QUEUE);
        amqpAdmin.deleteQueue(DLQ);
        amqpAdmin.deleteExchange(EXCHANGE);
        amqpAdmin.deleteExchange(DLX);
    }
}
