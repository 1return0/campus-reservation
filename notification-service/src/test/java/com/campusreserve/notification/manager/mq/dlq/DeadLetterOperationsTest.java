package com.campusreserve.notification.manager.mq.dlq;

import com.campusreserve.notification.dao.entity.NotificationInboxEventEntity;
import com.campusreserve.notification.dao.entity.StudentNotificationEntity;
import com.campusreserve.notification.dao.mapper.NotificationInboxEventMapper;
import com.campusreserve.notification.dao.mapper.StudentNotificationMapper;
import com.campusreserve.notification.service.impl.ReservationNotificationServiceImpl;
import com.campusreserve.notification.manager.mq.event.IncomingReservationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeadLetterOperationsTest {
    static final String ID = "550e8400-e29b-41d4-a716-446655440000";
    static final String SOURCE = "test.source";
    static final String EXCHANGE = "test.exchange";
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Connection connection = mock(Connection.class);
    final Channel deliveries = mock(Channel.class);
    final Channel publisher = mock(Channel.class);
    final DeadLetterInspector inspector = new DeadLetterInspector(mapper, SOURCE, EXCHANGE);
    DeadLetterOperations operations;
    GetResponse message;

    @BeforeEach
    void setUp() throws Exception {
        message = message(validBody());
        when(connection.createChannel()).thenReturn(deliveries, publisher);
        when(deliveries.basicGet("test.dlq", false)).thenReturn(message, (GetResponse) null);
        when(publisher.waitForConfirms(100)).thenReturn(true);
        operations = new DeadLetterOperations(connection, inspector, "test.dlq", EXCHANGE, 100);
    }

    byte[] validBody() {
        return ("{\"eventId\":\"" + ID + "\",\"schemaVersion\":1,\"eventType\":\"RESERVATION_CONFIRMED\","
                + "\"reservationId\":101,\"reservationNo\":\"private-number\",\"slotId\":102,"
                + "\"requesterReference\":\"private-student\",\"reservationStatus\":\"CONFIRMED\","
                + "\"occurredAt\":\"2026-10-16T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
    }

    GetResponse message(byte[] body) {
        return new GetResponse(new Envelope(7, false, "test.dlx", "reservation.invalid"),
                new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2)
                        .headers(Map.of("eventId", ID, "eventType", "RESERVATION_CONFIRMED",
                                "x-exception-message", "private-error", "x-death", List.of(Map.of(
                                        "queue", SOURCE, "exchange", EXCHANGE, "reason", "rejected", "count", 2L,
                                        "routing-keys", List.of("reservation.reservation_confirmed")))))
                        .build(), body, 0);
    }

    DeadLetterOperations.Replay replay(boolean confirmed) throws Exception {
        return operations.replay(ID, inspector.inspect(message).sha256(), 10, confirmed);
    }

    @Test void inspection_is_non_destructive_and_redacted() throws Exception {
        var result = operations.inspect(10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).deaths().get(0).count()).isEqualTo(2);
        assertThat(mapper.writeValueAsString(result)).doesNotContain("private-number", "private-student", "private-error");
        verify(deliveries, never()).basicAck(anyLong(), anyBoolean());
        verify(deliveries).close();
        verify(connection, times(1)).createChannel();
    }

    @Test void replay_defaults_to_dry_run() throws Exception {
        assertThat(replay(false).outcome()).isEqualTo("dry-run");
        verify(deliveries, never()).basicAck(anyLong(), anyBoolean());
        verify(connection, times(1)).createChannel();
    }

    @Test void confirms_and_checks_route_before_ack_preserving_exact_payload_and_properties() throws Exception {
        assertThat(replay(true).outcome()).isEqualTo("published-and-acked");
        var order = inOrder(publisher, deliveries);
        order.verify(publisher).basicPublish(eq(EXCHANGE), eq("reservation.reservation_confirmed"), eq(true),
                same(message.getProps()), same(message.getBody()));
        order.verify(publisher).waitForConfirms(100);
        order.verify(publisher).close();
        order.verify(deliveries).basicAck(7, false);
    }

    @Test void negative_confirm_keeps_source_unacked() throws Exception {
        when(publisher.waitForConfirms(100)).thenReturn(false);
        assertThatThrownBy(() -> replay(true)).hasMessage("publish-nack");
        verify(deliveries, never()).basicAck(anyLong(), anyBoolean());
        verify(deliveries).close();
    }

    @Test void confirm_timeout_keeps_source_unacked() throws Exception {
        when(publisher.waitForConfirms(100)).thenThrow(new TimeoutException());
        assertThatThrownBy(() -> replay(true)).hasMessage("publish-confirm-timeout-uncertain");
        verify(deliveries, never()).basicAck(anyLong(), anyBoolean());
        verify(deliveries).close();
    }

    @Test void send_failure_keeps_source_unacked() throws Exception {
        doThrow(new IOException("private-error")).when(publisher).basicPublish(anyString(), anyString(), anyBoolean(), any(), any());
        assertThatThrownBy(() -> replay(true)).hasMessage("publish-failed-or-uncertain");
        verify(deliveries, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test void return_even_with_positive_confirm_keeps_source_unacked() throws Exception {
        AtomicReference<ReturnListener> listener = new AtomicReference<>();
        doAnswer(call -> { listener.set(call.getArgument(0)); return null; })
                .when(publisher).addReturnListener(any(ReturnListener.class));
        when(publisher.waitForConfirms(100)).thenAnswer(call -> {
            listener.get().handleReturn(312, "NO_ROUTE", EXCHANGE, "reservation.reservation_confirmed",
                    message.getProps(), message.getBody());
            return true;
        });
        assertThatThrownBy(() -> replay(true)).hasMessage("publish-unroutable");
        verify(deliveries, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test void malformed_message_is_inspectable_but_cannot_be_replayed() throws Exception {
        message = message("not-json private-body".getBytes(StandardCharsets.UTF_8));
        when(deliveries.basicGet("test.dlq", false)).thenReturn(message);
        assertThat(replay(true).outcome()).isEqualTo("refused-invalid-message");
        verify(connection, times(1)).createChannel();
        verify(deliveries, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test void missing_death_origin_is_refused() throws Exception {
        message = new GetResponse(message.getEnvelope(), new AMQP.BasicProperties.Builder().headers(Map.of("eventId", ID)).build(), validBody(), 0);
        when(deliveries.basicGet("test.dlq", false)).thenReturn(message);
        assertThat(replay(true).outcome()).isEqualTo("refused-invalid-message");
    }

    @Test void nonpersistent_message_is_not_republished_and_removed() throws Exception {
        message = new GetResponse(message.getEnvelope(), message.getProps().builder().deliveryMode(1).build(), validBody(), 0);
        when(deliveries.basicGet("test.dlq", false)).thenReturn(message);
        assertThat(replay(true).outcome()).isEqualTo("refused-invalid-message");
        verify(deliveries, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test void ack_uncertainty_is_reported_after_confirm_without_automatic_republish() throws Exception {
        doThrow(new IOException("private-error")).when(deliveries).basicAck(7, false);
        assertThatThrownBy(() -> replay(true)).hasMessage("source-ack-uncertain-published-copy-exists");
        verify(publisher, times(1)).basicPublish(anyString(), anyString(), eq(true), any(), any());
    }

    @Test void selector_and_scan_are_bounded_without_discarding_other_messages() throws Exception {
        assertThat(operations.replay(ID, "0".repeat(64), 1, true).outcome()).isEqualTo("scan-limit-reached");
        verify(deliveries, never()).basicAck(anyLong(), anyBoolean());
        assertThatThrownBy(() -> operations.inspect(101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicate_replay_keeps_event_id_and_existing_inbox_deduplicates() throws Exception {
        var inbox = mock(NotificationInboxEventMapper.class);
        var notifications = mock(StudentNotificationMapper.class);
        var service = new ReservationNotificationServiceImpl(inbox, notifications, mapper);
        var seen = new HashSet<String>();
        when(inbox.insert(any(NotificationInboxEventEntity.class))).thenAnswer(call -> {
            NotificationInboxEventEntity row = call.getArgument(0);
            if (!seen.add(row.getEventId())) throw new DuplicateKeyException("duplicate");
            return 1;
        });
        var secondDeliveries = mock(Channel.class);
        var secondPublisher = mock(Channel.class);
        when(connection.createChannel()).thenReturn(deliveries, publisher, secondDeliveries, secondPublisher);
        when(secondDeliveries.basicGet("test.dlq", false)).thenReturn(message);
        when(secondPublisher.waitForConfirms(100)).thenReturn(true);
        assertThat(replay(true).outcome()).isEqualTo("published-and-acked");
        assertThat(replay(true).outcome()).isEqualTo("published-and-acked");
        var event = mapper.readValue(message.getBody(), IncomingReservationEvent.class);
        assertThat(service.accept(event)).isTrue();
        assertThat(service.accept(event)).isFalse();
        verify(notifications, times(1)).insert(any(StudentNotificationEntity.class));
    }
}
