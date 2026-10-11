package com.campusreserve.reservation.service;

import com.campusreserve.reservation.dto.req.CreateStudyTeamRequest;
import com.campusreserve.reservation.dto.resp.StudyTeamResponse;
import com.campusreserve.security.CampusAuthenticatedPrincipal;

import java.util.List;

public interface StudyTeamService {
    StudyTeamResponse create(CreateStudyTeamRequest request, CampusAuthenticatedPrincipal principal);
    StudyTeamResponse invite(Long teamId, String studentNumber, CampusAuthenticatedPrincipal principal);
    StudyTeamResponse accept(Long teamId, CampusAuthenticatedPrincipal principal);
    StudyTeamResponse decline(Long teamId, CampusAuthenticatedPrincipal principal);
    StudyTeamResponse join(String inviteCode, CampusAuthenticatedPrincipal principal);
    StudyTeamResponse leave(Long teamId, CampusAuthenticatedPrincipal principal);
    StudyTeamResponse close(Long teamId, CampusAuthenticatedPrincipal principal);
    StudyTeamResponse get(Long teamId, CampusAuthenticatedPrincipal principal);
    List<StudyTeamResponse> listMine(CampusAuthenticatedPrincipal principal);
}
