package com.campusreserve.reservation.manager.mq.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class LectureReservationListenerTest {
    @Test void malformed_and_unsupported_messages_are_not_automatically_requeued() {
        var worker = mock(LectureReservationWorker.class);
        var listener = new LectureReservationListener(new ObjectMapper(), worker);
        for (String body : new String[]{"not-json", "null", "{}", "{\"schemaVersion\":2,\"slotId\":7}"}) {
            assertThatThrownBy(() -> listener.consume(new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties())))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class).hasMessage("invalid-lecture-wakeup");
        }
        verifyNoInteractions(worker);
    }
    @Test void valid_message_is_only_a_signal_not_an_order_to_bypass_the_database_head() {
        var worker = mock(LectureReservationWorker.class);
        new LectureReservationListener(new ObjectMapper(), worker).consume(new Message(
                "{\"schemaVersion\":1,\"slotId\":7}".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        verify(worker).drain(7L);
    }
}
