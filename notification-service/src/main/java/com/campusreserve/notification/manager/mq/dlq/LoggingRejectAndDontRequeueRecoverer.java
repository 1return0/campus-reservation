package com.campusreserve.notification.manager.mq.dlq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;

/** Same recovery behavior; add only safe correlation codes to existing application logs. */
public class LoggingRejectAndDontRequeueRecoverer extends RejectAndDontRequeueRecoverer {
    private static final Logger log = LoggerFactory.getLogger(LoggingRejectAndDontRequeueRecoverer.class);
    private final int maxAttempts;

    public LoggingRejectAndDontRequeueRecoverer(int maxAttempts) { this.maxAttempts = maxAttempts; }

    @Override
    public void recover(Message message, Throwable cause) {
        boolean poison = false;
        String failureType = "unknown";
        for (Throwable error = cause; error != null; error = error.getCause()) {
            failureType = error.getClass().getSimpleName();
            if (error instanceof AmqpRejectAndDontRequeueException) { poison = true; break; }
        }
        log.warn("notification-consumer-dead-letter eventId={} eventType={} failureCode={} failureType={} attempts={}",
                DeadLetterInspector.safeEventId(message.getMessageProperties().getHeader("eventId")),
                DeadLetterInspector.safeType(message.getMessageProperties().getHeader("eventType")),
                poison ? "invalid-message" : "consumer-retries-exhausted", failureType, poison ? 1 : maxAttempts);
        // Do not call the superclass: its default warning can include the full message/body and exception.
        throw new AmqpRejectAndDontRequeueException("notification-event-dead-lettered");
    }
}
