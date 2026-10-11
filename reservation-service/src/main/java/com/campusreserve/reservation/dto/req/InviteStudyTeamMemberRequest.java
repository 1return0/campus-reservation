package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record InviteStudyTeamMemberRequest(
        @NotBlank @Pattern(regexp = "[0-9A-Za-z_-]{4,32}") String studentNumber
) {
}
