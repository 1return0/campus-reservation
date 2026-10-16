package com.campusreserve.notification.config;

import com.campusreserve.notification.manager.mq.dlq.DeadLetterQueueMonitor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "campus-reserve.messaging.dlq.monitor", name = "enabled", havingValue = "true")
public class DeadLetterMonitorConfiguration {
    @Bean
    DeadLetterQueueMonitor deadLetterQueueMonitor(RabbitTemplate template,
            @Value("${campus-reserve.messaging.dead-letter-queue:campus.notification.reservation.dlq}") String queue,
            @Value("${campus-reserve.messaging.dlq.monitor.alert-threshold:1}") long threshold) {
        return new DeadLetterQueueMonitor(template, queue, threshold);
    }
}
