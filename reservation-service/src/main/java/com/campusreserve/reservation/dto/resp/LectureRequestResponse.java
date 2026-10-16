package com.campusreserve.reservation.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record LectureRequestResponse(String requestId, String status,
        @JsonSerialize(using = ToStringSerializer.class) Long reservationId, String failureCode) { }
