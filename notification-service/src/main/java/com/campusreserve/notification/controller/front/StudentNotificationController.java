package com.campusreserve.notification.controller.front;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.common.api.PageResponse;
import com.campusreserve.notification.dto.resp.NotificationResponse;
import com.campusreserve.notification.service.StudentNotificationQueryService;
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
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasRole('STUDENT')")
public class StudentNotificationController {

    private final StudentNotificationQueryService notificationQueryService;

    @GetMapping("/me")
    public ApiResponse<PageResponse<NotificationResponse>> listMine(
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size
    ) {
        return ApiResponse.success(notificationQueryService.listMine(principal.userId(), page, size));
    }

    @PatchMapping("/{notificationId}/read")
    public void markRead(
            @PathVariable Long notificationId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal
    ) {
        notificationQueryService.markRead(notificationId, principal.userId());
    }
}
