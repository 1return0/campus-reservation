package com.campusreserve.reservation.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "campus-reserve.outbox.delivery", name = "enabled", havingValue = "true")
public class ReservationEventPublisherConfiguration {

    @Bean
    TopicExchange reservationEventsExchange(
            @Value("${campus-reserve.messaging.exchange:campus.reservation.events}") String exchangeName
    ) {
        return new TopicExchange(exchangeName, true, false);
    }
}
