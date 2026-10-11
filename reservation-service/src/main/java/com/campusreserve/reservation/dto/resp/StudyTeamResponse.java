package com.campusreserve.reservation.dto.resp;

import com.campusreserve.reservation.domain.StudyTeamStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;
import java.util.List;

public record StudyTeamResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long teamId,
        String leaderStudentNumber,
        StudyTeamStatus status,
        List<StudyTeamMemberResponse> members,
        LocalDateTime inviteCodeExpiresAt,
        String inviteCode
) {
}
