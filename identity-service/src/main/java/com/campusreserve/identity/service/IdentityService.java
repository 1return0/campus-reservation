package com.campusreserve.identity.service;

import com.campusreserve.identity.dto.resp.AuthResponse;
import com.campusreserve.identity.dto.req.CreateSupportStaffRequest;
import com.campusreserve.identity.dto.resp.IdentityUserResponse;
import com.campusreserve.identity.dto.req.LoginRequest;
import com.campusreserve.identity.dto.req.RegistrationRequest;

public interface IdentityService {

    void registerStudent(RegistrationRequest request);

    IdentityUserResponse createSupportStaff(CreateSupportStaffRequest request);

    AuthResponse login(LoginRequest request);
}
