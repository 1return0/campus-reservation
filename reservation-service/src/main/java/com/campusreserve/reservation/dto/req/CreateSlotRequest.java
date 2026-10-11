package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record CreateSlotRequest(
        @NotNull Long resourceId,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt,
        @NotNull @Min(1) Integer capacity,
        @Size(max = 160) String eventTitle,
        @Size(max = 120) String speakerName,
        @Size(max = 2000) String activityDescription
) {
}
