package com.campusreserve.reservation.dto.resp;

import com.campusreserve.reservation.domain.SlotStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

public record SlotResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long slotId,
        @JsonSerialize(using = ToStringSerializer.class) Long resourceId,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer capacity,
        Integer availableCapacity,
        SlotStatus status,
        String eventTitle,
        String speakerName,
        String activityDescription
) {
}
