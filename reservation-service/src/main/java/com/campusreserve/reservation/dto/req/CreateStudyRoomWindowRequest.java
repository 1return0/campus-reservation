package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record CreateStudyRoomWindowRequest(
        @NotNull Long resourceId,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt
) { }
