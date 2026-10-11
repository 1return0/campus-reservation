package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelSlotRequest(@NotBlank @Size(max = 300) String reason) {
}
