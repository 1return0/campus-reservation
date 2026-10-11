package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateResourceRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9_-]{3,64}") String resourceCode,
        @NotBlank @Size(max = 128) String resourceName,
        @NotBlank @Size(max = 32) String resourceType,
        @Min(1) @Max(60) Integer noShowGraceMinutes
) {
}
