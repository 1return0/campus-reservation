package com.campusreserve.identity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.identity.dto.resp.AuthResponse;
import com.campusreserve.identity.dto.req.CreateSupportStaffRequest;
import com.campusreserve.identity.dto.resp.IdentityUserResponse;
import com.campusreserve.identity.dto.req.LoginRequest;
import com.campusreserve.identity.dto.req.RegistrationRequest;
import com.campusreserve.identity.service.IdentityService;
import com.campusreserve.identity.domain.CampusRole;
import com.campusreserve.identity.domain.IdentityUserStatus;
import com.campusreserve.identity.dao.entity.IdentityUserEntity;
import com.campusreserve.identity.dao.mapper.IdentityUserMapper;
import com.campusreserve.security.CampusJwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class IdentityServiceImpl implements IdentityService {

    private final IdentityUserMapper identityUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final CampusJwtService jwtService;

    @Value("${campus-reserve.security.jwt.access-token-ttl-seconds:3600}")
    private long accessTokenTtlSeconds;

    @Override
    @Transactional
    public void registerStudent(RegistrationRequest request) {
        createUser(request.username(), request.password(), request.displayName(),
                request.studentNumber(), CampusRole.STUDENT);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public IdentityUserResponse createSupportStaff(CreateSupportStaffRequest request) {
        IdentityUserEntity user = createUser(
                request.username(), request.password(), request.displayName(), null, CampusRole.SUPPORT_STAFF
        );
        return toResponse(user);
    }

    private IdentityUserEntity createUser(
            String username,
            String password,
            String displayName,
            String studentNumber,
            CampusRole role
    ) {
        IdentityUserEntity user = new IdentityUserEntity();
        user.setUsername(username);
        user.setStudentNumber(studentNumber);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setRoleCode(role);
        user.setStatus(IdentityUserStatus.ACTIVE);
        try {
            identityUserMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "username-already-exists", "username-already-exists");
        }
        return user;
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        IdentityUserEntity user = identityUserMapper.selectOne(
                new LambdaQueryWrapper<IdentityUserEntity>()
                        .eq(IdentityUserEntity::getUsername, request.username())
        );
        if (user == null
                || user.getStatus() != IdentityUserStatus.ACTIVE
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Do not reveal whether an account exists or is disabled.
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "invalid-credentials", "invalid-credentials");
        }
        String token = jwtService.issue(
                user.getId().toString(),
                List.of(user.getRoleCode().name()),
                user.getStudentNumber(),
                Duration.ofSeconds(accessTokenTtlSeconds)
        );
        return new AuthResponse(token, accessTokenTtlSeconds);
    }

    private static IdentityUserResponse toResponse(IdentityUserEntity user) {
        return new IdentityUserResponse(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRoleCode(),
                user.getStatus(), user.getCreatedAt()
        );
    }
}
