package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;
import com.campusreserve.reservation.domain.ReservationEventType;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LecturePublisherTest {
    @Test void commands_use_a_separate_exchange_and_wait_for_confirm() { exercise("ack"); }
    @Test void nack_is_not_a_successful_publication() { exercise("nack"); }
    @Test void broker_return_is_failure_even_with_positive_confirm() { exercise("return"); }

    void exercise(String result) {
        var rabbit = mock(RabbitTemplate.class);
        var publisher = new RabbitReservationOutboxPublisher(rabbit);
        ReflectionTestUtils.setField(publisher, "exchange", "notifications");
        ReflectionTestUtils.setField(publisher, "lectureExchange", "commands");
        ReflectionTestUtils.setField(publisher, "confirmTimeoutMillis", 100L);
        publisher.configureMandatoryReturns();
        verify(rabbit).setMandatory(true);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            if (result.equals("return")) correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", "commands", "lecture.reserve"));
            correlation.getFuture().complete(new CorrelationData.Confirm(!result.equals("nack"), "test"));
            return null;
        }).when(rabbit).convertAndSend(eq("commands"), eq("lecture.reserve"), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        var event = new ReservationOutboxEventEntity();
        event.setEventId("test-event");
        event.setAggregateId(7L);
        event.setEventType(ReservationEventType.LECTURE_RESERVATION_REQUESTED);
        event.setPayload("{\"schemaVersion\":1,\"slotId\":7}");
        if (result.equals("ack")) assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
        else assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(result.equals("nack") ? "rabbitmq-broker-nack" : "rabbitmq-message-unroutable");
    }
}
