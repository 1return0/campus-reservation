package com.campusreserve.notification;

import com.campusreserve.notification.manager.mq.dlq.DeadLetterInspector;
import com.campusreserve.notification.manager.mq.dlq.DeadLetterOperations;
import com.campusreserve.notification.manager.mq.dlq.DeadLetterQueueMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: real, isolated Broker/DLX/DLQ + real notification Inbox/MySQL. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_DLQ_LIVE_SMOKE", matches = "true")
@SpringBootTest(classes = NotificationServiceApplication.class, properties = {
        "NACOS_DISCOVERY_ENABLED=false", "RESERVATION_EVENT_CONSUMER_ENABLED=true",
        "NOTIFICATION_DLQ_MONITOR_ENABLED=false",
        "campus-reserve.messaging.consumer.retry.initial-interval-millis=10",
        "campus-reserve.messaging.consumer.retry.max-interval-millis=20",
        "debug=false", "logging.level.root=INFO",
        "logging.level.org.springframework=INFO", "logging.level.com.campusreserve=INFO"
})
class NotificationDlqLiveSmoke {
    static final String RUN = UUID.randomUUID().toString().replace("-", "");
    static final String EXCHANGE = "campus.dlq-smoke." + RUN;
    static final String QUEUE = EXCHANGE + ".source";
    static final String DLX = EXCHANGE + ".dlx";
    static final String DLQ = EXCHANGE + ".dlq";
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        registry.add("campus-reserve.messaging.exchange", () -> EXCHANGE);
        registry.add("campus-reserve.messaging.queue", () -> QUEUE);
        registry.add("campus-reserve.messaging.dead-letter-exchange", () -> DLX);
        registry.add("campus-reserve.messaging.dead-letter-queue", () -> DLQ);
    }
    @Autowired CachingConnectionFactory connectionFactory;
    @Autowired ObjectMapper mapper;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired JdbcTemplate jdbc;
    String eventId;
    Connection connection;
    DeadLetterOperations operations;
    byte[] originalBody;

    @BeforeEach void prepare() throws Exception {
        listeners.stop();
        ((org.springframework.amqp.rabbit.core.RabbitAdmin) admin).initialize();
        connection = connectionFactory.getRabbitConnectionFactory().newConnection("campus-dlq-isolated-smoke");
        operations = new DeadLetterOperations(connection, new DeadLetterInspector(mapper, QUEUE, EXCHANGE), DLQ, EXCHANGE, 3000);
        eventId = UUID.randomUUID().toString();
    }

    byte[] validBody() throws Exception {
        if (originalBody != null) return originalBody;
        originalBody = mapper.writeValueAsBytes(Map.of("eventId", eventId, "eventType", "RESERVATION_CONFIRMED",
                "schemaVersion", 1, "reservationId", 991L, "reservationNo", "dlq-smoke-" + RUN.substring(0, 12),
                "slotId", 992L, "requesterReference", "dlq-smoke-" + RUN,
                "reservationStatus", "CONFIRMED", "occurredAt", Instant.now().toString()));
        return originalBody;
    }

    void publish(byte[] body) throws Exception {
        try (Channel channel = connection.createChannel()) {
            channel.confirmSelect();
            channel.basicPublish(EXCHANGE, "reservation.reservation_confirmed", true,
                    new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2)
                            .headers(Map.of("eventId", eventId, "eventType", "RESERVATION_CONFIRMED")).build(), body);
            assertThat(channel.waitForConfirms(3000)).isTrue();
        }
    }

    void deadLetterValid() throws Exception {
        publish(validBody());
        await(() -> depth(QUEUE) == 1);
        try (Channel channel = connection.createChannel()) {
            GetResponse message = channel.basicGet(QUEUE, false);
            assertThat(message).isNotNull();
            channel.basicReject(message.getEnvelope().getDeliveryTag(), false);
        }
        await(() -> depth(DLQ) == 1);
        var status = new DeadLetterQueueMonitor(new org.springframework.amqp.rabbit.core.RabbitTemplate(connectionFactory),
                DLQ, 1).status();
        assertThat(status.readyMessages()).isEqualTo(1);
        assertThat(status.backlogAlert()).isTrue();
    }

    long depth(String queue) {
        try (Channel channel = connection.createChannel()) { return channel.queueDeclarePassive(queue).getMessageCount(); }
        catch (Exception exception) { throw new IllegalStateException("isolated-queue-unavailable"); }
    }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) throw new AssertionError("isolated-dlq-smoke-timeout");
            Thread.sleep(50);
        }
    }
    int count(String table, String column) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, eventId);
    }

    @Test void confirmed_replay_and_duplicate_replay_produce_one_real_notification() throws Exception {
        for (int delivery = 0; delivery < 2; delivery++) {
            deadLetterValid();
            var view = operations.inspect(1).get(0);
            assertThat(view.replayable()).isTrue();
            assertThat(operations.replay(eventId, view.sha256(), 1, false).outcome()).isEqualTo("dry-run");
            assertThat(depth(DLQ)).isEqualTo(1);
            assertThat(operations.replay(eventId, view.sha256(), 1, true).outcome()).isEqualTo("published-and-acked");
            assertThat(depth(DLQ)).isZero();
            listeners.start();
            await(() -> depth(QUEUE) == 0 && count("student_notification", "source_event_id") == 1);
            listeners.stop(); // waits for in-flight listener processing/ACK before assertions.
            assertThat(count("notification_inbox_event", "event_id")).isEqualTo(1);
            assertThat(count("student_notification", "source_event_id")).isEqualTo(1);
        }
    }

    @Test void mandatory_return_preserves_original_dlq_message() throws Exception {
        deadLetterValid();
        var view = operations.inspect(1).get(0);
        admin.removeBinding(new Binding(QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, "reservation.#", Map.of()));
        assertThatThrownBy(() -> operations.replay(eventId, view.sha256(), 1, true)).hasMessage("publish-unroutable");
        assertThat(depth(DLQ)).isEqualTo(1);
        assertThat(operations.inspect(1).get(0).sha256()).isEqualTo(view.sha256());
    }

    @Test void real_listener_sends_poison_to_dlq_and_manual_replay_refuses_it() throws Exception {
        listeners.start();
        publish("invalid-json-do-not-expose".getBytes(StandardCharsets.UTF_8));
        await(() -> depth(DLQ) == 1);
        var view = operations.inspect(1).get(0);
        assertThat(view.replayable()).isFalse();
        assertThat(view.deaths().get(0).reason()).isEqualTo("rejected");
        assertThat(operations.replay(eventId, view.sha256(), 1, true).outcome()).isEqualTo("refused-invalid-message");
        assertThat(depth(DLQ)).isEqualTo(1);
        assertThat(count("notification_inbox_event", "event_id")).isZero();
    }

    @AfterEach void cleanupOnlyThisTestsFixtures() throws Exception {
        listeners.stop();
        if (connection != null) connection.close();
        if (eventId != null) {
            jdbc.update("DELETE FROM student_notification WHERE source_event_id=?", eventId);
            jdbc.update("DELETE FROM notification_inbox_event WHERE event_id=?", eventId);
            assertThat(count("student_notification", "source_event_id")).isZero();
            assertThat(count("notification_inbox_event", "event_id")).isZero();
        }
        assertThat(admin.deleteQueue(QUEUE)).isTrue();
        assertThat(admin.deleteQueue(DLQ)).isTrue();
        admin.deleteExchange(EXCHANGE);
        admin.deleteExchange(DLX);
    }
}
