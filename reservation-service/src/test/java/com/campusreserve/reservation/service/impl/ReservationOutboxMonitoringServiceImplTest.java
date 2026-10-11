package com.campusreserve.reservation.service.impl;

import com.campusreserve.reservation.dto.resp.OutboxDeliverySummaryResponse;
import com.campusreserve.reservation.domain.OutboxPublishStatus;
import com.campusreserve.reservation.dao.mapper.ReservationOutboxEventMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationOutboxMonitoringServiceImplTest {

    @Test
    void returns_counts_and_oldest_pending_timestamp_without_event_payload() {
        ReservationOutboxEventMapper mapper = mock(ReservationOutboxEventMapper.class);
        when(mapper.countByPublishStatus(OutboxPublishStatus.PENDING)).thenReturn(3L);
        when(mapper.countByPublishStatus(OutboxPublishStatus.LEASED)).thenReturn(2L);
        when(mapper.oldestPendingCreatedAt()).thenReturn(Instant.parse("2026-10-03T08:00:00Z"));

        OutboxDeliverySummaryResponse result = new ReservationOutboxMonitoringServiceImpl(mapper).summary();

        assertThat(result.pendingEvents()).isEqualTo(3);
        assertThat(result.activelyLeasedEvents()).isEqualTo(2);
        assertThat(result.oldestPendingCreatedAt()).isEqualTo(Instant.parse("2026-10-03T08:00:00Z"));
    }
}
