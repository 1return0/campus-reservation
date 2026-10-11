package com.campusreserve.reservation.controller.admin;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.common.api.PageResponse;
import com.campusreserve.reservation.dto.resp.ReservationWorkItemResponse;
import com.campusreserve.reservation.service.ReservationService;
import com.campusreserve.reservation.domain.ReservationStatus;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/reservations")
@PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
public class StaffReservationController {

    private final ReservationService reservationService;

    @GetMapping
    public ApiResponse<PageResponse<ReservationWorkItemResponse>> listWorkQueue(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size
    ) {
        return ApiResponse.success(reservationService.listWorkQueue(status, page, size));
    }

    @PatchMapping("/{reservationId}/check-in")
    public ApiResponse<ReservationWorkItemResponse> checkIn(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(reservationService.checkIn(reservationId, principal.userId()));
    }

    @PatchMapping("/{reservationId}/complete")
    public ApiResponse<ReservationWorkItemResponse> complete(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(reservationService.complete(reservationId, principal.userId()));
    }

    @PatchMapping("/{reservationId}/no-show")
    public ApiResponse<ReservationWorkItemResponse> markNoShow(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(reservationService.markNoShow(reservationId, principal.userId()));
    }
}
