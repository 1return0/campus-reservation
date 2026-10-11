package com.campusreserve.identity.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 32) String username,
        @NotBlank @Size(max = 72) String password
) {
}
