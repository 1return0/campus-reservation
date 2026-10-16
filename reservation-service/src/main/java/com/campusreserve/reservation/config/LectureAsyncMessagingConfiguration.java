package com.campusreserve.reservation.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(prefix = "campus-reserve.outbox.delivery", name = "enabled", havingValue = "true")
public class LectureAsyncMessagingConfiguration {
    @Bean
    Declarables lectureCommandTopology(
            @Value("${campus-reserve.lecture-async.exchange:campus.lecture.commands}") String exchange,
            @Value("${campus-reserve.lecture-async.queue:campus.lecture.reserve}") String queue) {
        var commands = new DirectExchange(exchange, true, false);
        var dead = new DirectExchange(exchange + ".dlx", true, false);
        var work = QueueBuilder.durable(queue).deadLetterExchange(dead.getName()).deadLetterRoutingKey("dead").build();
        var dlq = QueueBuilder.durable(queue + ".dlq").build();
        return new Declarables(commands, dead, work, dlq,
                BindingBuilder.bind(work).to(commands).with("lecture.reserve"),
                BindingBuilder.bind(dlq).to(dead).with("dead"));
    }

    @Bean
    SimpleRabbitListenerContainerFactory lectureListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConcurrentConsumers(2);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
