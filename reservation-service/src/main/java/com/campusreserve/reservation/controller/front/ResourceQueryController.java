package com.campusreserve.reservation.controller.front;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.reservation.dto.resp.ResourceResponse;
import com.campusreserve.reservation.dto.resp.SlotResponse;
import com.campusreserve.reservation.service.ResourceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/resources")
@PreAuthorize("hasRole('STUDENT')")
public class ResourceQueryController {

    private final ResourceQueryService resourceQueryService;

    @GetMapping
    public ApiResponse<List<ResourceResponse>> listPublishedResources() {
        return ApiResponse.success(resourceQueryService.listPublishedResources());
    }

    @GetMapping("/{resourceId}/slots")
    public ApiResponse<List<SlotResponse>> listOpenSlots(@PathVariable Long resourceId) {
        return ApiResponse.success(resourceQueryService.listOpenSlots(resourceId));
    }
}
