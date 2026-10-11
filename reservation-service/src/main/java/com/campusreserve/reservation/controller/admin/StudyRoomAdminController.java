package com.campusreserve.reservation.controller.admin;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.reservation.dto.req.CreateStudyRoomWindowRequest;
import com.campusreserve.reservation.dto.req.UpdateStudyRoomOccupancyRequest;
import com.campusreserve.reservation.dto.req.StudyRoomCheckInRequest;
import com.campusreserve.reservation.dto.req.ReplaceStudyRoomWindowRequest;
import com.campusreserve.reservation.dto.resp.StudyRoomBookingResponse;
import com.campusreserve.reservation.dto.resp.StudyRoomWindowResponse;
import com.campusreserve.reservation.service.StudyRoomService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.campusreserve.security.CampusAuthenticatedPrincipal;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/study-rooms")
@PreAuthorize("hasAnyRole('SUPPORT_STAFF','ADMIN')")
public class StudyRoomAdminController {
    private final StudyRoomService service;

    @PostMapping("/windows")
    public ApiResponse<StudyRoomWindowResponse> open(@Valid @RequestBody CreateStudyRoomWindowRequest request) {
        return ApiResponse.success(service.openWindow(request));
    }

    @PostMapping("/windows/{windowId}/maintenance-close")
    public ApiResponse<StudyRoomWindowResponse> close(@PathVariable Long windowId,
            @RequestParam @NotBlank @Size(max = 512) String reason) {
        return ApiResponse.success(service.closeWindowForMaintenance(windowId, reason));
    }

    @PatchMapping("/windows/{windowId}")
    public ApiResponse<StudyRoomWindowResponse> shorten(@PathVariable Long windowId,
            @Valid @RequestBody ReplaceStudyRoomWindowRequest request) {
        return ApiResponse.success(service.shortenWindow(windowId, request));
    }

    @PatchMapping("/{resourceId}/occupancy")
    public ApiResponse<Void> occupancy(@PathVariable Long resourceId,
            @Valid @RequestBody UpdateStudyRoomOccupancyRequest request) {
        service.updateOccupancy(resourceId, request);
        return ApiResponse.success(null);
    }

    @GetMapping("/bookings")
    public ApiResponse<List<StudyRoomBookingResponse>> workQueue() {
        return ApiResponse.success(service.workQueue());
    }

    @PatchMapping("/bookings/{bookingId}/check-in")
    public ApiResponse<StudyRoomBookingResponse> checkIn(@PathVariable Long bookingId,
            @Valid @RequestBody StudyRoomCheckInRequest request,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(service.checkIn(bookingId, principal.userId(), request.studentNumber()));
    }

    @PatchMapping("/bookings/{bookingId}/complete")
    public ApiResponse<StudyRoomBookingResponse> complete(@PathVariable Long bookingId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(service.complete(bookingId, principal.userId()));
    }

    @PatchMapping("/bookings/{bookingId}/no-show")
    public ApiResponse<StudyRoomBookingResponse> markNoShow(@PathVariable Long bookingId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(service.markNoShow(bookingId, principal.userId()));
    }
}
