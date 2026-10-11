package com.campusreserve.identity.controller.admin;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.identity.service.IdentityService;
import com.campusreserve.identity.dto.req.CreateSupportStaffRequest;
import com.campusreserve.identity.dto.resp.IdentityUserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/identity")
@PreAuthorize("hasRole('ADMIN')")
public class IdentityAdminController {

    private final IdentityService identityService;

    @PostMapping("/support-staff")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IdentityUserResponse> createSupportStaff(
            @Valid @RequestBody CreateSupportStaffRequest request
    ) {
        return ApiResponse.success(identityService.createSupportStaff(request));
    }
}
