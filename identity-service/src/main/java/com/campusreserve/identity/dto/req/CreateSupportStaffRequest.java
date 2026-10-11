package com.campusreserve.identity.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSupportStaffRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{4,32}") String username,
        @NotBlank @Size(min = 12, max = 72) String password,
        @NotBlank @Size(max = 64) String displayName
) {
}
