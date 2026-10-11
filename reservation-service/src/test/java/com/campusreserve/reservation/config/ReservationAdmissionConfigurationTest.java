package com.campusreserve.reservation.config;

import com.campusreserve.reservation.manager.rate.RedisReservationAdmissionGuard;
import com.campusreserve.reservation.manager.rate.ReservationAdmissionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReservationAdmissionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ReservationAdmissionConfiguration.class);

    @Test
    void supplies_a_no_op_guard_when_admission_is_disabled_or_omitted() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ReservationAdmissionGuard.class);
            assertThat(context.getBean(ReservationAdmissionGuard.class))
                    .isNotInstanceOf(RedisReservationAdmissionGuard.class);
        });
    }

    @Test
    void supplies_the_redis_lua_guard_only_when_explicitly_enabled() {
        contextRunner
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withPropertyValues("campus-reserve.admission.rate-limit.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ReservationAdmissionGuard.class);
                    assertThat(context.getBean(ReservationAdmissionGuard.class))
                            .isInstanceOf(RedisReservationAdmissionGuard.class);
                });
    }
}
