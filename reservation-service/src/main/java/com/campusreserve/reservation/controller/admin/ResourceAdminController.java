package com.campusreserve.reservation.controller.admin;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.reservation.dto.req.CreateResourceRequest;
import com.campusreserve.reservation.dto.req.CreateSlotRequest;
import com.campusreserve.reservation.dto.req.CancelSlotRequest;
import com.campusreserve.reservation.dto.resp.ResourceResponse;
import com.campusreserve.reservation.dto.resp.SlotResponse;
import com.campusreserve.reservation.dto.resp.SlotCancellationResponse;
import com.campusreserve.reservation.service.ResourceManagementService;
import com.campusreserve.reservation.service.SlotCancellationService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
public class ResourceAdminController {

    private final ResourceManagementService resourceManagementService;
    private final SlotCancellationService slotCancellationService;

    @PostMapping("/resources")
    public ApiResponse<ResourceResponse> createResource(@Valid @RequestBody CreateResourceRequest request) {
        return ApiResponse.success(resourceManagementService.createResource(request));
    }

    @PatchMapping("/resources/{resourceId}/publish")
    public ApiResponse<ResourceResponse> publishResource(@PathVariable Long resourceId) {
        return ApiResponse.success(resourceManagementService.publishResource(resourceId));
    }

    @PatchMapping("/resources/{resourceId}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ResourceResponse> closeResource(@PathVariable Long resourceId) {
        return ApiResponse.success(resourceManagementService.closeResource(resourceId));
    }

    @GetMapping("/resources")
    public ApiResponse<List<ResourceResponse>> listResources() {
        return ApiResponse.success(resourceManagementService.listAllResources());
    }

    @GetMapping("/resources/{resourceId}/slots")
    public ApiResponse<List<SlotResponse>> listSlots(@PathVariable Long resourceId) {
        return ApiResponse.success(resourceManagementService.listSlots(resourceId));
    }

    @PostMapping("/slots")
    public ApiResponse<SlotResponse> createSlot(@Valid @RequestBody CreateSlotRequest request) {
        return ApiResponse.success(resourceManagementService.createOpenSlot(request));
    }

    @PatchMapping("/slots/{slotId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SlotCancellationResponse> cancelSlot(
            @PathVariable Long slotId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal,
            @Valid @RequestBody CancelSlotRequest request
    ) {
        return ApiResponse.success(slotCancellationService.cancelForMaintenance(slotId, principal.userId(), request));
    }

    @GetMapping("/slots/{slotId}/cancellation")
    public ApiResponse<SlotCancellationResponse> getSlotCancellation(@PathVariable Long slotId) {
        return ApiResponse.success(slotCancellationService.getCancellation(slotId));
    }
}
