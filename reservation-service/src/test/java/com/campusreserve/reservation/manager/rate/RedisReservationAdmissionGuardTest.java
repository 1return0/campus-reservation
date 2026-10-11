package com.campusreserve.reservation.manager.rate;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.config.ReservationAdmissionRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisReservationAdmissionGuardTest {

    @Test
    void accepts_a_request_when_the_lua_script_returns_the_admitted_marker() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        stubScriptResult(redisTemplate, -1L);
        RedisReservationAdmissionGuard guard = new RedisReservationAdmissionGuard(redisTemplate, properties());

        guard.requireAdmission(11L, "student-1001");
    }

    @Test
    void maps_a_lua_limit_result_to_a_stable_429_business_error() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        stubScriptResult(redisTemplate, 735L);
        RedisReservationAdmissionGuard guard = new RedisReservationAdmissionGuard(redisTemplate, properties());

        assertThatThrownBy(() -> guard.requireAdmission(11L, "student-1001"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getStatus().value()).isEqualTo(429);
                    assertThat(business.getCode()).isEqualTo("reservation-admission-rate-limited");
                });
    }

    @Test
    void fails_closed_with_503_when_enabled_redis_admission_is_unavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(),
                any(), any(), any()
        ))
                .thenThrow(new DataAccessResourceFailureException("redis-down"));
        RedisReservationAdmissionGuard guard = new RedisReservationAdmissionGuard(redisTemplate, properties());

        assertThatThrownBy(() -> guard.requireAdmission(11L, "student-1001"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getStatus().value()).isEqualTo(503);
                    assertThat(business.getCode()).isEqualTo("reservation-admission-unavailable");
                });
    }

    private static void stubScriptResult(StringRedisTemplate redisTemplate, Long result) {
        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(),
                any(), any(), any()
        ))
                .thenReturn(result);
    }

    private static ReservationAdmissionRateLimitProperties properties() {
        ReservationAdmissionRateLimitProperties properties = new ReservationAdmissionRateLimitProperties();
        properties.setWindowMillis(1_000);
        properties.setPerSlotMaxRequests(80);
        properties.setPerUserPerSlotMaxRequests(3);
        return properties;
    }
}
