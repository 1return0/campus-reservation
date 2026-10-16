package com.campusreserve.notification.manager.mq.dlq;

import com.campusreserve.notification.manager.mq.consumer.ReservationEventConsumer;
import com.campusreserve.notification.manager.mq.event.IncomingReservationEvent;
import com.campusreserve.notification.service.ReservationNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeadLetterRetryPolicyTest {
    public static class ListenerAdapter {
        private final ReservationEventConsumer consumer;
        public ListenerAdapter(ReservationEventConsumer consumer) { this.consumer = consumer; }
        // Spring's retry interceptor wraps ChannelAwareMessageListener, not the one-argument bean method.
        public void onMessage(com.rabbitmq.client.Channel channel, Message message) { consumer.consume(message); }
    }

    ListenerAdapter proxy(ReservationNotificationService service, ObjectMapper mapper) {
        var factory = new ProxyFactory(new ListenerAdapter(new ReservationEventConsumer(service, mapper)));
        factory.setProxyTargetClass(true);
        factory.addAdvice(RetryInterceptorBuilder.stateless().retryPolicy(new SimpleRetryPolicy(3,
                        Map.of(AmqpRejectAndDontRequeueException.class, false), true, true))
                .backOffOptions(1, 2, 2).recoverer(new LoggingRejectAndDontRequeueRecoverer(3)).build());
        return (ListenerAdapter) factory.getProxy();
    }

    @Test void poison_message_is_not_retried() throws Exception {
        var service = mock(ReservationNotificationService.class);
        var mapper = spy(new ObjectMapper().findAndRegisterModules());
        var message = new Message("invalid private-payload".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        assertThatThrownBy(() -> proxy(service, mapper).onMessage(null, message)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(mapper, times(1)).readValue(anyString(), eq(IncomingReservationEvent.class));
        verifyNoInteractions(service);
    }

    @Test void transient_failure_is_bounded_to_three_attempts_then_rejected() {
        var service = mock(ReservationNotificationService.class);
        when(service.accept(any())).thenThrow(new IllegalStateException("private-database-details"));
        var properties = new MessageProperties();
        properties.setHeader("eventId", DeadLetterOperationsTest.ID);
        var fixture = new DeadLetterOperationsTest();
        var message = new Message(fixture.validBody(), properties);
        assertThatThrownBy(() -> proxy(service, fixture.mapper).onMessage(null, message)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(service, times(3)).accept(any());
    }
}
