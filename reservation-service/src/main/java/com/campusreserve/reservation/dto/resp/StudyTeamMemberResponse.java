package com.campusreserve.reservation.dto.resp;

import com.campusreserve.reservation.domain.StudyTeamMemberStatus;

public record StudyTeamMemberResponse(String studentNumber, StudyTeamMemberStatus status) {
}
