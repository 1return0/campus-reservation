package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record StudyTeamJoinRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{16,32}") String inviteCode
) {
}
