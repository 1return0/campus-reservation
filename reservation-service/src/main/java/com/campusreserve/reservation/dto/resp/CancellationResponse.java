package com.campusreserve.reservation.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record CancellationResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long cancelledReservationId,
        String promotedReservationNo
) {
}
