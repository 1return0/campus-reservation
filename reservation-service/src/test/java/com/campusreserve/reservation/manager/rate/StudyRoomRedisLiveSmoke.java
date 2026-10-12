package com.campusreserve.reservation.manager.rate;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.config.StudyRoomAdmissionRateLimitProperties;
import com.campusreserve.reservation.config.ReservationAdmissionRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in real Redis/Lua smoke; never starts MySQL or mutates booking rows. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_REDIS_LIVE_SMOKE", matches = "true")
class StudyRoomRedisLiveSmoke {
    @Test
    void user_and_room_day_limits_are_atomic_and_independent() {
        String host = System.getenv().getOrDefault("RESERVATION_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("RESERVATION_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration(host, port);
        String password = System.getenv("RESERVATION_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            redis.setPassword(RedisPassword.of(password));
        }
        LettuceConnectionFactory connection = new LettuceConnectionFactory(redis);
        connection.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connection);
        template.afterPropertiesSet();
        long roomId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        LocalDate date = LocalDate.of(2026, 10, 10);
        var properties = new StudyRoomAdmissionRateLimitProperties();
        properties.setWindowMillis(60_000);
        properties.setPerRoomDayMaxRequests(3);
        properties.setPerUserPerRoomDayMaxRequests(2);
        var guard = new RedisStudyRoomAdmissionGuard(template, properties);
        String scope = "campus-reserve:study-room-admission:{room:" + roomId + ":" + date + "}";
        try {
            guard.requireAdmission(roomId, date, "student-a");
            guard.requireAdmission(roomId, date, "student-a");
            assertThatThrownBy(() -> guard.requireAdmission(roomId, date, "student-a"))
                    .isInstanceOf(BusinessException.class).hasMessage("study-room-admission-rate-limited");
            guard.requireAdmission(roomId, date, "student-b");
            assertThatThrownBy(() -> guard.requireAdmission(roomId, date, "student-c"))
                    .isInstanceOf(BusinessException.class).hasMessage("study-room-admission-rate-limited");
            guard.requireAdmission(roomId + 1, date, "student-c");
        } finally {
            template.delete(List.of(scope + ":total", scope + ":user:student-a",
                    scope + ":user:student-b", scope + ":user:student-c",
                    "campus-reserve:study-room-admission:{room:" + (roomId + 1) + ":" + date + "}:total",
                    "campus-reserve:study-room-admission:{room:" + (roomId + 1) + ":" + date + "}:user:student-c"));
            connection.destroy();
        }
    }

    @Test
    void concurrent_room_day_requests_admit_no_more_than_the_configured_limit() throws Exception {
        int requestCount = 500;
        int workerCount = 500;
        int admissionLimit = 20;
        String host = System.getenv().getOrDefault("RESERVATION_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("RESERVATION_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration(host, port);
        String password = System.getenv("RESERVATION_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) { redis.setPassword(RedisPassword.of(password)); }
        LettuceConnectionFactory connection = new LettuceConnectionFactory(redis);
        connection.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connection);
        template.afterPropertiesSet();
        long roomId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        LocalDate date = LocalDate.of(2026, 10, 10);
        var properties = new StudyRoomAdmissionRateLimitProperties();
        properties.setWindowMillis(60_000);
        properties.setPerRoomDayMaxRequests(admissionLimit);
        properties.setPerUserPerRoomDayMaxRequests(1);
        var guard = new RedisStudyRoomAdmissionGuard(template, properties);
        var workers = Executors.newFixedThreadPool(workerCount);
        var ready = new CountDownLatch(workerCount);
        var start = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peakActive = new AtomicInteger();
        String scope = "campus-reserve:study-room-admission:{room:" + roomId + ":" + date + "}";
        try {
            try (var redisConnection = connection.getConnection()) {
                assertThat(redisConnection.ping()).isEqualTo("PONG");
            }
            var results = IntStream.range(0, requestCount).mapToObj(index -> workers.submit(() -> {
                ready.countDown();
                start.await();
                int currentActive = active.incrementAndGet();
                peakActive.accumulateAndGet(currentActive, Math::max);
                try {
                    guard.requireAdmission(roomId, date, "parallel-student-" + index);
                    return true;
                } catch (BusinessException rejected) {
                    if (!"study-room-admission-rate-limited".equals(rejected.getCode())) { throw rejected; }
                    return false;
                } finally {
                    active.decrementAndGet();
                }
            })).toList();
            assertThat(ready.await(20, TimeUnit.SECONDS))
                    .as("all 500 workers must be ready before releasing the burst")
                    .isTrue();
            long burstStarted = System.nanoTime();
            long deadline = burstStarted + TimeUnit.SECONDS.toNanos(30);
            start.countDown();
            int admitted = 0;
            for (var result : results) {
                long remaining = Math.max(1, deadline - System.nanoTime());
                if (result.get(remaining, TimeUnit.NANOSECONDS)) { admitted++; }
            }
            int rejected = requestCount - admitted;
            assertThat(admitted).isEqualTo(admissionLimit);
            assertThat(rejected).isEqualTo(480);
            assertThat(active.get()).isZero();
            assertThat(template.opsForValue().get(scope + ":total")).isEqualTo("500");
            System.out.printf("PASS Redis Lua burst: requests=%d, workers=%d, admitted=%d, "
                            + "rejected=%d, peak_active_calls=%d, elapsed_ms=%d%n",
                    requestCount, workerCount, admitted, rejected, peakActive.get(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - burstStarted));
        } finally {
            start.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            var keys = new java.util.ArrayList<String>();
            keys.add(scope + ":total");
            IntStream.range(0, requestCount).forEach(index -> keys.add(scope + ":user:parallel-student-" + index));
            template.delete(keys);
            connection.destroy();
        }
    }

    @Test
    void legacy_slot_limiter_still_runs_with_cluster_compatible_keys() {
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("RESERVATION_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("RESERVATION_REDIS_PORT", "6379")));
        String password = System.getenv("RESERVATION_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) { redis.setPassword(RedisPassword.of(password)); }
        LettuceConnectionFactory connection = new LettuceConnectionFactory(redis);
        connection.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connection);
        template.afterPropertiesSet();
        long slotId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        var properties = new ReservationAdmissionRateLimitProperties();
        properties.setWindowMillis(60_000);
        properties.setPerSlotMaxRequests(2);
        properties.setPerUserPerSlotMaxRequests(1);
        var guard = new RedisReservationAdmissionGuard(template, properties);
        String scope = "campus-reserve:reservation-admission:{slot:" + slotId + "}";
        try {
            guard.requireAdmission(slotId, "student-a");
            guard.requireAdmission(slotId, "student-b");
            assertThatThrownBy(() -> guard.requireAdmission(slotId, "student-c"))
                    .isInstanceOf(BusinessException.class).hasMessage("reservation-admission-rate-limited");
        } finally {
            template.delete(List.of(scope + ":total", scope + ":user:student-a",
                    scope + ":user:student-b", scope + ":user:student-c"));
            connection.destroy();
        }
    }
}
