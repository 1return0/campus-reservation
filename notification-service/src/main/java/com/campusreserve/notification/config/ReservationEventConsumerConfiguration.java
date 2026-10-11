package com.campusreserve.notification.config;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

@Configuration
@ConditionalOnProperty(
        prefix = "campus-reserve.messaging.consumer",
        name = "enabled",
        havingValue = "true"
)
public class ReservationEventConsumerConfiguration {

    @Bean
    TopicExchange reservationEventsExchange(
            @Value("${campus-reserve.messaging.exchange:campus.reservation.events}") String exchangeName
    ) {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    DirectExchange notificationDeadLetterExchange(
            @Value("${campus-reserve.messaging.dead-letter-exchange:campus.notification.dlx}") String exchangeName
    ) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    Queue reservationNotificationQueue(
            @Value("${campus-reserve.messaging.queue:campus.notification.reservation.v1}") String queueName,
            @Value("${campus-reserve.messaging.dead-letter-exchange:campus.notification.dlx}") String deadLetterExchange,
            @Value("${campus-reserve.messaging.dead-letter-routing-key:reservation.invalid}") String deadLetterRoutingKey
    ) {
        return QueueBuilder.durable(queueName)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }

    @Bean
    Queue reservationNotificationDeadLetterQueue(
            @Value("${campus-reserve.messaging.dead-letter-queue:campus.notification.reservation.dlq}") String queueName
    ) {
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    Binding reservationEventBinding(
            Queue reservationNotificationQueue,
            TopicExchange reservationEventsExchange
    ) {
        return BindingBuilder.bind(reservationNotificationQueue)
                .to(reservationEventsExchange)
                .with("reservation.#");
    }

    @Bean
    Binding reservationEventDeadLetterBinding(
            Queue reservationNotificationDeadLetterQueue,
            DirectExchange notificationDeadLetterExchange,
            @Value("${campus-reserve.messaging.dead-letter-routing-key:reservation.invalid}") String routingKey
    ) {
        return BindingBuilder.bind(reservationNotificationDeadLetterQueue)
                .to(notificationDeadLetterExchange)
                .with(routingKey);
    }

    @Bean
    SimpleRabbitListenerContainerFactory reservationEventRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${campus-reserve.messaging.consumer.retry.max-attempts:3}") int maxAttempts,
            @Value("${campus-reserve.messaging.consumer.retry.initial-interval-millis:1000}") long initialIntervalMillis,
            @Value("${campus-reserve.messaging.consumer.retry.multiplier:2.0}") double multiplier,
            @Value("${campus-reserve.messaging.consumer.retry.max-interval-millis:5000}") long maxIntervalMillis
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryPolicy(new SimpleRetryPolicy(
                        maxAttempts,
                        Map.of(AmqpRejectAndDontRequeueException.class, false),
                        true,
                        true
                ))
                .backOffOptions(initialIntervalMillis, multiplier, maxIntervalMillis)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
