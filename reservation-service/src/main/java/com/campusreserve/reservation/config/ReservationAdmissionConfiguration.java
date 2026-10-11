package com.campusreserve.reservation.config;

import com.campusreserve.reservation.manager.rate.RedisReservationAdmissionGuard;
import com.campusreserve.reservation.manager.rate.ReservationAdmissionGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(ReservationAdmissionRateLimitProperties.class)
public class ReservationAdmissionConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "campus-reserve.admission.rate-limit",
            name = "enabled",
            havingValue = "true"
    )
    ReservationAdmissionGuard redisReservationAdmissionGuard(
            StringRedisTemplate redisTemplate,
            ReservationAdmissionRateLimitProperties properties
    ) {
        return new RedisReservationAdmissionGuard(redisTemplate, properties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "campus-reserve.admission.rate-limit",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    ReservationAdmissionGuard noOpReservationAdmissionGuard() {
        return (slotId, requesterReference) -> {
            // The normal campus reservation path remains available without Redis.
        };
    }
}
