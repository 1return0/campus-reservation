package com.campusreserve.reservation.dto.resp;

import com.campusreserve.reservation.domain.ReservationStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/** Staff-facing operational view. It exposes only data needed to fulfil a reservation. */
public record ReservationWorkItemResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long reservationId,
        String reservationNo,
        @JsonSerialize(using = ToStringSerializer.class) Long slotId,
        String requesterReference,
        ReservationStatus status,
        String handledByReference,
        LocalDateTime checkedInAt,
        LocalDateTime completedAt,
        LocalDateTime noShowAt,
        LocalDateTime createdAt
) {
}
