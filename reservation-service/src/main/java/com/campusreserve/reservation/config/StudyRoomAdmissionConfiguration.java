package com.campusreserve.reservation.config;

import com.campusreserve.reservation.manager.rate.RedisStudyRoomAdmissionGuard;
import com.campusreserve.reservation.manager.rate.StudyRoomAdmissionGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(StudyRoomAdmissionRateLimitProperties.class)
public class StudyRoomAdmissionConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "campus-reserve.admission.study-room", name = "enabled", havingValue = "true")
    StudyRoomAdmissionGuard redisStudyRoomAdmissionGuard(StringRedisTemplate redisTemplate,
            StudyRoomAdmissionRateLimitProperties properties) {
        return new RedisStudyRoomAdmissionGuard(redisTemplate, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "campus-reserve.admission.study-room", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    StudyRoomAdmissionGuard noOpStudyRoomAdmissionGuard() {
        return (roomId, useDate, requesterReference) -> { };
    }
}
