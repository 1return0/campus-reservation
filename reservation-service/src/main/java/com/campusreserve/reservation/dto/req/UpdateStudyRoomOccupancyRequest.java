package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateStudyRoomOccupancyRequest(
        @NotNull @Min(1) @Max(20) Integer minParticipants,
        @NotNull @Min(1) @Max(20) Integer maxParticipants
) { }
