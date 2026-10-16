package com.campusreserve.notification.manager.mq.dlq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Passive declarations only: never creates/deletes queues or consumes a DLQ message. */
public class DeadLetterQueueMonitor {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueMonitor.class);
    private final RabbitTemplate template;
    private final String queue;
    private final long threshold;

    public DeadLetterQueueMonitor(RabbitTemplate template, String queue, long threshold) {
        if (threshold < 1) throw new IllegalArgumentException("invalid-dlq-threshold");
        this.template = template;
        this.queue = queue;
        this.threshold = threshold;
    }

    public record Status(long readyMessages, int consumers, boolean backlogAlert,
                         String countScope) { }

    public Status status() {
        return template.execute(channel -> {
            var info = channel.queueDeclarePassive(queue);
            return new Status(info.getMessageCount(), info.getConsumerCount(), info.getMessageCount() >= threshold,
                    "ready-only; use RabbitMQ Management UI for unacknowledged/total counts");
        });
    }

    @Scheduled(fixedDelayString = "${campus-reserve.messaging.dlq.monitor.interval-millis:60000}")
    public void check() {
        try {
            Status status = status();
            if (status.backlogAlert()) log.warn("notification-dlq-backlog ready={} consumers={} threshold={}",
                    status.readyMessages(), status.consumers(), threshold);
        } catch (Exception ignored) { log.warn("notification-dlq-monitor-unavailable"); }
    }
}
