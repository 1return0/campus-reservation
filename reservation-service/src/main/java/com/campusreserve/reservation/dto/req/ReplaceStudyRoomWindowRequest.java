package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record ReplaceStudyRoomWindowRequest(
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt,
        @NotBlank @Size(max = 512) String reason
) { }
