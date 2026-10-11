package com.campusreserve.reservation.controller.front;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.reservation.dto.req.CreateStudyRoomBookingRequest;
import com.campusreserve.reservation.dto.resp.StudyRoomBookingResponse;
import com.campusreserve.reservation.dto.resp.StudyRoomWindowResponse;
import com.campusreserve.reservation.dto.resp.StudyRoomAvailabilityResponse;
import com.campusreserve.reservation.service.StudyRoomService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/study-rooms")
public class StudyRoomController {
    private final StudyRoomService service;

    @GetMapping("/{resourceId}/windows")
    public ApiResponse<List<StudyRoomWindowResponse>> windows(@PathVariable Long resourceId) {
        return ApiResponse.success(service.listWindows(resourceId));
    }

    @GetMapping("/{resourceId}/availability")
    public ApiResponse<StudyRoomAvailabilityResponse> availability(@PathVariable Long resourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(service.availability(resourceId, date));
    }

    @PostMapping("/bookings")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<StudyRoomBookingResponse> book(
            @Valid @RequestBody CreateStudyRoomBookingRequest request,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String idempotencyKey,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(service.book(request, idempotencyKey, principal));
    }

    @GetMapping("/bookings/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<List<StudyRoomBookingResponse>> mine(
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(service.listMine(principal));
    }

    @GetMapping("/bookings/{bookingId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<StudyRoomBookingResponse> get(@PathVariable Long bookingId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(service.get(bookingId, principal));
    }

    @DeleteMapping("/bookings/{bookingId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<StudyRoomBookingResponse> cancel(@PathVariable Long bookingId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(service.cancel(bookingId, principal));
    }
}
