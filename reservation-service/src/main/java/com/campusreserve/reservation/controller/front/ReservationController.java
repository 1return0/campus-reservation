package com.campusreserve.reservation.controller.front;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.reservation.dto.resp.CancellationResponse;
import com.campusreserve.reservation.dto.resp.ReservationResponse;
import com.campusreserve.reservation.service.ReservationService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping("/slots/{slotId}/reservations")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<ReservationResponse> reserve(
            @PathVariable Long slotId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal,
            @RequestHeader("Idempotency-Key") @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String idempotencyKey
    ) {
        return ApiResponse.success(reservationService.reserve(slotId, principal.userId(), idempotencyKey));
    }

    @GetMapping("/reservations/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<List<ReservationResponse>> listMine(
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(reservationService.listMine(principal.userId()));
    }

    @GetMapping("/reservations/{reservationId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<ReservationResponse> getById(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(reservationService.getById(reservationId, principal.userId()));
    }

    @DeleteMapping("/reservations/{reservationId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<CancellationResponse> cancel(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(reservationService.cancel(reservationId, principal.userId()));
    }
}
