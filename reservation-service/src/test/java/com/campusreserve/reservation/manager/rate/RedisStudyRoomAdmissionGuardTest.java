package com.campusreserve.reservation.manager.rate;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.config.StudyRoomAdmissionRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStudyRoomAdmissionGuardTest {
    private static final LocalDate USE_DATE = LocalDate.of(2026, 10, 10);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisStudyRoomAdmissionGuard guard = new RedisStudyRoomAdmissionGuard(
            redisTemplate, new StudyRoomAdmissionRateLimitProperties());

    @Test
    void admitted_request_uses_room_day_and_student_keys_in_one_cluster_slot() {
        stub(-1L);
        guard.requireAdmission(42L, USE_DATE, "student-7");

        verify(redisTemplate).execute(any(RedisScript.class), argThat((List<String> keys) ->
                        keys.size() == 2 && keys.get(0).contains("{room:42:2026-10-10}")
                                && keys.get(1).contains("{room:42:2026-10-10}")
                                && keys.get(0).endsWith(":user:student-7")
                                && keys.get(1).endsWith(":total")),
                org.mockito.ArgumentMatchers.eq("1000"),
                org.mockito.ArgumentMatchers.eq("3"),
                org.mockito.ArgumentMatchers.eq("80"));
    }

    @Test
    void rejected_request_is_429_and_redis_outage_is_503() {
        stub(0L);
        assertThatThrownBy(() -> guard.requireAdmission(42L, USE_DATE, "student-7"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    var business = (BusinessException) error;
                    assertThat(business.getStatus().value()).isEqualTo(429);
                    assertThat(business.getCode()).isEqualTo("study-room-admission-rate-limited");
                });

        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        assertThatThrownBy(() -> guard.requireAdmission(42L, USE_DATE, "student-7"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    var business = (BusinessException) error;
                    assertThat(business.getStatus().value()).isEqualTo(503);
                    assertThat(business.getCode()).isEqualTo("study-room-admission-unavailable");
                });
    }

    private void stub(Long result) {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .thenReturn(result);
    }
}
