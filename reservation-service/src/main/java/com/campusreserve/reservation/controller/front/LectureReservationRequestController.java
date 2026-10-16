package com.campusreserve.reservation.controller.front;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.reservation.dto.resp.LectureRequestResponse;
import com.campusreserve.reservation.service.LectureReservationRequestService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('STUDENT')")
public class LectureReservationRequestController {
    private final LectureReservationRequestService service;

    @PostMapping("/slots/{slotId}/reservation-requests")
    public ResponseEntity<ApiResponse<LectureRequestResponse>> submit(@PathVariable Long slotId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String key) {
        var result = service.submit(slotId, principal.userId(), key);
        return ResponseEntity.accepted().location(URI.create("/api/v1/reservation-requests/" + result.requestId()))
                .body(ApiResponse.success(result));
    }

    @GetMapping("/reservation-requests/{requestId}")
    public ApiResponse<LectureRequestResponse> get(@PathVariable String requestId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(service.get(requestId, principal.userId()));
    }
}
