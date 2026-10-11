package com.campusreserve.reservation.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

public record SlotCancellationResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long slotId,
        String operatorReference,
        String reason,
        int confirmedCount,
        int waitlistedCount,
        LocalDateTime cancelledAt
) {
}
