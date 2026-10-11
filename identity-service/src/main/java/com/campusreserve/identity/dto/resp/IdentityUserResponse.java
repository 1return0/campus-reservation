package com.campusreserve.identity.dto.resp;

import com.campusreserve.identity.domain.CampusRole;
import com.campusreserve.identity.domain.IdentityUserStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

public record IdentityUserResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String username,
        String displayName,
        CampusRole role,
        IdentityUserStatus status,
        Instant createdAt
) {
}
