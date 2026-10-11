package com.campusreserve.reservation.config;

import com.campusreserve.reservation.domain.StudyRoomWindowStatus;
import com.campusreserve.reservation.dto.resp.StudyRoomWindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.cache.Cache;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in verification of the actual Redis cache serializer and window DTO. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_REDIS_LIVE_SMOKE", matches = "true")
class StudyRoomWindowRedisCacheLiveSmoke {
    @Test
    void window_list_round_trips_and_can_be_evicted() {
        var redis = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("RESERVATION_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("RESERVATION_REDIS_PORT", "6379")));
        String password = System.getenv("RESERVATION_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) { redis.setPassword(RedisPassword.of(password)); }
        var connection = new LettuceConnectionFactory(redis);
        connection.afterPropertiesSet();
        var properties = new ReservationCacheConfiguration.ReservationCacheProperties();
        var mapper = new ObjectMapper().findAndRegisterModules();
        Cache cache = new ReservationCacheConfiguration()
                .reservationRedisCacheManager(connection, mapper, properties)
                .getCache("study-room-windows");
        String key = "smoke-" + UUID.randomUUID();
        var windows = List.of(new StudyRoomWindowResponse("1", "42",
                LocalDateTime.of(2026, 10, 10, 7, 30),
                LocalDateTime.of(2026, 10, 10, 21, 30), StudyRoomWindowStatus.OPEN));
        try {
            cache.put(key, windows);
            assertThat(cache.get(key).get()).isEqualTo(windows);
        } finally {
            cache.evict(key);
            connection.destroy();
        }
    }
}
