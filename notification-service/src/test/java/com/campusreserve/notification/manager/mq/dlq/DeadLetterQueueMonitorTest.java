package com.campusreserve.notification.manager.mq.dlq;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeadLetterQueueMonitorTest {
    @Test void detects_backlog_and_warns_without_reading_messages() throws Exception {
        var channel = mock(Channel.class);
        var template = mock(RabbitTemplate.class);
        when(channel.queueDeclarePassive("test.dlq")).thenReturn(new AMQP.Queue.DeclareOk.Builder()
                .queue("test.dlq").messageCount(3).consumerCount(0).build());
        when(template.execute(any())).thenAnswer(call -> ((ChannelCallback<?>) call.getArgument(0)).doInRabbit(channel));
        var monitor = new DeadLetterQueueMonitor(template, "test.dlq", 1);
        assertThat(monitor.status().backlogAlert()).isTrue();
        var logs = new ListAppender<ILoggingEvent>(); logs.start();
        var logger = (Logger) LoggerFactory.getLogger(DeadLetterQueueMonitor.class);
        logger.addAppender(logs);
        try { monitor.check(); }
        finally { logger.detachAppender(logs); }
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("notification-dlq-backlog", "ready=3"));
        verify(channel, never()).basicGet(anyString(), anyBoolean());
    }

    @Test void missing_queue_or_broker_does_not_crash_scheduler_or_log_credentials() {
        var template = mock(RabbitTemplate.class);
        when(template.execute(any())).thenThrow(new AmqpException("private-connection-details"));
        new DeadLetterQueueMonitor(template, "test.dlq", 1).check();
    }
}
