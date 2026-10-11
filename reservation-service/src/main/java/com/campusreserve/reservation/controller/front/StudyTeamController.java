package com.campusreserve.reservation.controller.front;

import com.campusreserve.common.api.ApiResponse;
import com.campusreserve.reservation.dto.req.CreateStudyTeamRequest;
import com.campusreserve.reservation.dto.req.InviteStudyTeamMemberRequest;
import com.campusreserve.reservation.dto.req.StudyTeamJoinRequest;
import com.campusreserve.reservation.dto.resp.StudyTeamResponse;
import com.campusreserve.reservation.service.StudyTeamService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/study-teams")
@PreAuthorize("hasRole('STUDENT')")
public class StudyTeamController {

    private final StudyTeamService teamService;

    @PostMapping
    public ApiResponse<StudyTeamResponse> create(
            @Valid @RequestBody CreateStudyTeamRequest request,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(teamService.create(request, principal));
    }

    @GetMapping("/mine")
    public ApiResponse<List<StudyTeamResponse>> listMine(
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(teamService.listMine(principal));
    }

    @GetMapping("/{teamId}")
    public ApiResponse<StudyTeamResponse> get(@PathVariable Long teamId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(teamService.get(teamId, principal));
    }

    @PostMapping("/{teamId}/invitations")
    public ApiResponse<StudyTeamResponse> invite(@PathVariable Long teamId,
            @Valid @RequestBody InviteStudyTeamMemberRequest request,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(teamService.invite(teamId, request.studentNumber(), principal));
    }

    @PostMapping("/{teamId}/accept")
    public ApiResponse<StudyTeamResponse> accept(@PathVariable Long teamId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(teamService.accept(teamId, principal));
    }

    @PostMapping("/{teamId}/decline")
    public ApiResponse<StudyTeamResponse> decline(@PathVariable Long teamId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(teamService.decline(teamId, principal));
    }

    @PostMapping("/join")
    public ApiResponse<StudyTeamResponse> join(@Valid @RequestBody StudyTeamJoinRequest request,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(teamService.join(request.inviteCode(), principal));
    }

    @DeleteMapping("/{teamId}/members/me")
    public ApiResponse<StudyTeamResponse> leave(@PathVariable Long teamId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(teamService.leave(teamId, principal));
    }

    @PostMapping("/{teamId}/close")
    public ApiResponse<StudyTeamResponse> close(@PathVariable Long teamId,
            @AuthenticationPrincipal CampusAuthenticatedPrincipal principal) {
        return ApiResponse.success(teamService.close(teamId, principal));
    }
}
