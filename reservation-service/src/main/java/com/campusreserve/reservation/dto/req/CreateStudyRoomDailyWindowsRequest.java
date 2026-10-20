package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/** Opens the same daily interval for every date in the inclusive range. */
public record CreateStudyRoomDailyWindowsRequest(
        @NotNull Long resourceId,
        @NotNull LocalDate startsOn,
        @NotNull LocalDate endsOn,
        @NotNull LocalTime dailyStartsAt,
        @NotNull LocalTime dailyEndsAt
) { }
