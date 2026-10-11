package com.campusreserve.identity.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{4,32}") String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 64) String displayName,
        @Pattern(regexp = "[0-9A-Za-z_-]{4,32}") String studentNumber
) {
}
