package com.campusreserve.reservation.controller.admin;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.reservation.dto.resp.OutboxDeliverySummaryResponse;
import com.campusreserve.reservation.service.ReservationOutboxMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/operations")
@PreAuthorize("hasRole('ADMIN')")
public class ReservationOperationsController {

    private final ReservationOutboxMonitoringService outboxMonitoringService;

    @GetMapping("/outbox-summary")
    public ApiResponse<OutboxDeliverySummaryResponse> outboxSummary() {
        return ApiResponse.success(outboxMonitoringService.summary());
    }
}
