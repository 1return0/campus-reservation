package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.reservation.manager.mq.event.LectureReservationWakeup;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "campus-reserve.outbox.delivery", name = "enabled", havingValue = "true")
public class LectureReservationListener {
    private final ObjectMapper json;
    private final LectureReservationWorker worker;

    @RabbitListener(queues = "${campus-reserve.lecture-async.queue:campus.lecture.reserve}",
            containerFactory = "lectureListenerFactory")
    public void consume(Message message) {
        LectureReservationWakeup wakeup;
        try {
            wakeup = json.readValue(message.getBody(), LectureReservationWakeup.class);
            if (wakeup.schemaVersion() != 1 || wakeup.slotId() == null || wakeup.slotId() <= 0)
                throw new IllegalArgumentException("invalid-wakeup");
        } catch (Exception malformed) {
            // Poison messages go to DLQ once. No raw payload/identity in the exception.
            throw new AmqpRejectAndDontRequeueException("invalid-lecture-wakeup");
        }
        worker.drain(wakeup.slotId());
        // ACK only after the attempt commits/rolls back. Deferred rows have DB recovery.
    }
}
