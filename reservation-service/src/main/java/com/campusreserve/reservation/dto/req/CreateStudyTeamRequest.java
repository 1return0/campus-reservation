package com.campusreserve.reservation.dto.req;

import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateStudyTeamRequest(
        @Size(max = 19) List<String> invitedStudentNumbers
) {
}
