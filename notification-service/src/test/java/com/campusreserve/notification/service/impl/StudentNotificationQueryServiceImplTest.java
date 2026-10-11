package com.campusreserve.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusreserve.common.api.PageResponse;
import com.campusreserve.notification.dao.entity.StudentNotificationEntity;
import com.campusreserve.notification.dao.mapper.StudentNotificationMapper;
import com.campusreserve.notification.dto.resp.NotificationResponse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentNotificationQueryServiceImplTest {

    @Test
    void lists_only_the_authenticated_recipient_in_a_stable_page() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "notification-test"),
                StudentNotificationEntity.class
        );
        StudentNotificationMapper mapper = mock(StudentNotificationMapper.class);
        StudentNotificationQueryServiceImpl service = new StudentNotificationQueryServiceImpl(mapper);
        Instant createdAt = Instant.parse("2026-10-08T08:00:00Z");
        StudentNotificationEntity notification = new StudentNotificationEntity(
                31L, "notification-31", "student-1001", "event-31",
                "RESERVATION_CONFIRMED", "Reservation confirmed", "Reservation R-31 status: CONFIRMED",
                null, createdAt
        );
        Page<StudentNotificationEntity> databasePage = Page.of(2, 2);
        databasePage.setRecords(List.of(notification));
        databasePage.setTotal(3);
        when(mapper.selectPage(any(), any())).thenReturn(databasePage);

        PageResponse<NotificationResponse> response = service.listMine("student-1001", 2, 2);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.total()).isEqualTo(3);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).id()).isEqualTo(31L);
        AtomicReference<Wrapper<StudentNotificationEntity>> capturedQuery = new AtomicReference<>();
        verify(mapper).selectPage(
                argThat(requestedPage -> requestedPage.getCurrent() == 2 && requestedPage.getSize() == 2),
                argThat(query -> {
                    capturedQuery.set(query);
                    return true;
                })
        );
        assertThat(capturedQuery.get().getSqlSegment())
                .contains("recipient_reference", "created_at", "id", "ORDER BY");
        assertThat(((LambdaQueryWrapper<?>) capturedQuery.get()).getParamNameValuePairs())
                .containsValue("student-1001");
    }
}
