package com.campusreserve.reservation.dto.resp;

import com.campusreserve.reservation.domain.ReservationStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

public record ReservationResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long reservationId,
        String reservationNo,
        @JsonSerialize(using = ToStringSerializer.class) Long slotId,
        ReservationStatus status,
        Integer waitlistPosition,
        LocalDateTime createdAt
) {
}
