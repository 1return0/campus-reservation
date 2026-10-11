package com.campusreserve.reservation.service;

import com.campusreserve.reservation.dto.resp.OutboxDeliverySummaryResponse;

public interface ReservationOutboxMonitoringService {

    OutboxDeliverySummaryResponse summary();
}
