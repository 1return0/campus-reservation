package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record CreateStudyRoomBookingRequest(
        @NotNull Long resourceId,
        @NotNull Long teamId,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt
) { }
