package com.campusreserve.reservation.config;

import com.campusreserve.reservation.manager.rate.RedisStudyRoomAdmissionGuard;
import com.campusreserve.reservation.manager.rate.StudyRoomAdmissionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StudyRoomAdmissionConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StudyRoomAdmissionConfiguration.class);

    @Test
    void defaults_to_no_op_without_redis() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(StudyRoomAdmissionGuard.class);
            assertThat(context.getBean(StudyRoomAdmissionGuard.class))
                    .isNotInstanceOf(RedisStudyRoomAdmissionGuard.class);
        });
    }

    @Test
    void explicitly_enabled_uses_redis_guard() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withPropertyValues("campus-reserve.admission.study-room.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(StudyRoomAdmissionGuard.class);
                    assertThat(context.getBean(StudyRoomAdmissionGuard.class))
                            .isInstanceOf(RedisStudyRoomAdmissionGuard.class);
                });
    }
}
