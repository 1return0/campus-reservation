package com.campusreserve.reservation.service.impl;

import com.campusreserve.reservation.dto.resp.OutboxDeliverySummaryResponse;
import com.campusreserve.reservation.domain.OutboxPublishStatus;
import com.campusreserve.reservation.dao.mapper.ReservationOutboxEventMapper;
import com.campusreserve.reservation.service.ReservationOutboxMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Read-only operations projection for alerting and incident triage. */
@Service
@RequiredArgsConstructor
public class ReservationOutboxMonitoringServiceImpl implements ReservationOutboxMonitoringService {

    private final ReservationOutboxEventMapper outboxEventMapper;

    @Override
    public OutboxDeliverySummaryResponse summary() {
        long pending = outboxEventMapper.countByPublishStatus(OutboxPublishStatus.PENDING);
        long leased = outboxEventMapper.countByPublishStatus(OutboxPublishStatus.LEASED);
        return new OutboxDeliverySummaryResponse(
                pending,
                leased,
                outboxEventMapper.oldestPendingCreatedAt()
        );
    }
}
