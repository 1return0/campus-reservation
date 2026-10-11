package com.campusreserve.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dao.entity.StudyTeamEntity;
import com.campusreserve.reservation.dao.entity.StudyTeamMemberEntity;
import com.campusreserve.reservation.dao.mapper.StudyTeamMapper;
import com.campusreserve.reservation.dao.mapper.StudyTeamMemberMapper;
import com.campusreserve.reservation.domain.StudyTeamMemberStatus;
import com.campusreserve.reservation.domain.StudyTeamStatus;
import com.campusreserve.reservation.dto.req.CreateStudyTeamRequest;
import com.campusreserve.reservation.dto.resp.StudyTeamMemberResponse;
import com.campusreserve.reservation.dto.resp.StudyTeamResponse;
import com.campusreserve.reservation.service.StudyTeamService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StudyTeamServiceImpl implements StudyTeamService {

    private static final int MAX_MEMBERS = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StudyTeamMapper teamMapper;
    private final StudyTeamMemberMapper memberMapper;
    private final Clock reservationClock;

    @Override
    @Transactional
    @PreAuthorize("hasRole('STUDENT')")
    public StudyTeamResponse create(CreateStudyTeamRequest request, CampusAuthenticatedPrincipal principal) {
        String leaderNumber = requireStudentNumber(principal);
        List<String> invitees = request == null || request.invitedStudentNumbers() == null
                ? List.of() : request.invitedStudentNumbers();
        if (invitees.size() >= MAX_MEMBERS) { throw badRequest("team-too-large"); }
        Set<String> unique = new HashSet<>();
        unique.add(leaderNumber);
        for (String number : invitees) {
            if (!validNumber(number) || !unique.add(number)) {
                throw badRequest("invalid-or-duplicate-student-number");
            }
        }
        String inviteCode = newInviteCode();
        StudyTeamEntity team = new StudyTeamEntity();
        team.setLeaderReference(principal.userId());
        team.setInviteCodeHash(hash(inviteCode));
        team.setInviteCodeExpiresAt(now().plusHours(24));
        team.setStatus(StudyTeamStatus.FORMING);
        teamMapper.insert(team);
        insertMember(team.getId(), leaderNumber, principal.userId(), StudyTeamMemberStatus.ACCEPTED);
        for (String number : invitees) {
            insertMember(team.getId(), number, null, StudyTeamMemberStatus.INVITED);
        }
        return response(team, inviteCode);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('STUDENT')")
    public StudyTeamResponse invite(Long teamId, String studentNumber, CampusAuthenticatedPrincipal principal) {
        requireStudentNumber(principal);
        if (!validNumber(studentNumber)) { throw badRequest("invalid-student-number"); }
        StudyTeamEntity team = lockForming(teamId);
        requireLeader(team, principal);
        StudyTeamMemberEntity existing = member(teamId, studentNumber);
        if (existing != null && (existing.getStatus() == StudyTeamMemberStatus.ACCEPTED
                || existing.getStatus() == StudyTeamMemberStatus.INVITED)) {
            throw conflict("team-member-already-present");
        }
        if (members(teamId).stream().filter(entry -> entry.getStatus() == StudyTeamMemberStatus.ACCEPTED
                || entry.getStatus() == StudyTeamMemberStatus.INVITED).count() >= MAX_MEMBERS) {
            throw conflict("team-too-large");
        }
        if (existing == null) {
            insertMember(teamId, studentNumber, null, StudyTeamMemberStatus.INVITED);
        } else {
            existing.setStatus(StudyTeamMemberStatus.INVITED);
            existing.setUserReference(null);
            existing.setAcceptedAt(null);
            memberMapper.updateById(existing);
        }
        return response(team, null);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('STUDENT')")
    public StudyTeamResponse accept(Long teamId, CampusAuthenticatedPrincipal principal) {
        String number = requireStudentNumber(principal);
        StudyTeamEntity team = lockForming(teamId);
        StudyTeamMemberEntity invitation = member(teamId, number);
        if (invitation == null) { throw notFound("team-invitation-not-found"); }
        acceptMember(invitation, principal, false);
        return response(team, null);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('STUDENT')")
    public StudyTeamResponse decline(Long teamId, CampusAuthenticatedPrincipal principal) {
        String number = requireStudentNumber(principal);
        StudyTeamEntity team = lockForming(teamId);
        StudyTeamMemberEntity invitation = member(teamId, number);
        if (invitation == null) { throw notFound("team-invitation-not-found"); }
        if (memberMapper.decline(invitation.getId()) != 1) {
            throw conflict("team-invitation-not-active");
        }
        return response(team, null);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('STUDENT')")
    public StudyTeamResponse join(String inviteCode, CampusAuthenticatedPrincipal principal) {
        String number = requireStudentNumber(principal);
        if (inviteCode == null || !inviteCode.matches("[A-Za-z0-9_-]{16,32}")) {
            throw badRequest("invalid-invite-code");
        }
        StudyTeamEntity found = teamMapper.selectOne(new LambdaQueryWrapper<StudyTeamEntity>()
                .eq(StudyTeamEntity::getInviteCodeHash, hash(inviteCode)));
        if (found == null) { throw notFound("team-invite-code-not-found"); }
        StudyTeamEntity team = lockForming(found.getId());
        if (!team.getInviteCodeExpiresAt().isAfter(now())) { throw conflict("team-invite-code-expired"); }
        StudyTeamMemberEntity existing = member(team.getId(), number);
        if (existing != null) {
            acceptMember(existing, principal, true);
        } else {
            if (members(team.getId()).stream().filter(entry -> entry.getStatus() == StudyTeamMemberStatus.ACCEPTED
                    || entry.getStatus() == StudyTeamMemberStatus.INVITED).count() >= MAX_MEMBERS) {
                throw conflict("team-too-large");
            }
            insertMember(team.getId(), number, principal.userId(), StudyTeamMemberStatus.ACCEPTED);
        }
        return response(team, null);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('STUDENT')")
    public StudyTeamResponse leave(Long teamId, CampusAuthenticatedPrincipal principal) {
        String number = requireStudentNumber(principal);
        StudyTeamEntity team = lockForming(teamId);
        if (team.getLeaderReference().equals(principal.userId())) { throw conflict("team-leader-cannot-leave"); }
        StudyTeamMemberEntity existing = member(teamId, number);
        if (existing == null || existing.getStatus() != StudyTeamMemberStatus.ACCEPTED
                || !principal.userId().equals(existing.getUserReference())) {
            throw notFound("team-membership-not-found");
        }
        if (memberMapper.leave(existing.getId(), principal.userId()) != 1) {
            throw conflict("team-member-state-conflict");
        }
        return response(team, null);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('STUDENT')")
    public StudyTeamResponse close(Long teamId, CampusAuthenticatedPrincipal principal) {
        requireStudentNumber(principal);
        StudyTeamEntity team = lockForming(teamId);
        requireLeader(team, principal);
        if (teamMapper.closeForming(teamId) != 1) { throw conflict("team-state-conflict"); }
        team.setStatus(StudyTeamStatus.CLOSED);
        return response(team, null);
    }

    @Override
    @PreAuthorize("hasRole('STUDENT')")
    public StudyTeamResponse get(Long teamId, CampusAuthenticatedPrincipal principal) {
        String number = requireStudentNumber(principal);
        StudyTeamEntity team = teamMapper.selectById(teamId);
        StudyTeamMemberEntity own = member(teamId, number);
        if (team == null || own == null || (own.getStatus() != StudyTeamMemberStatus.INVITED
                && own.getStatus() != StudyTeamMemberStatus.ACCEPTED)) {
            throw notFound("team-not-found");
        }
        return response(team, null);
    }

    @Override
    @PreAuthorize("hasRole('STUDENT')")
    public List<StudyTeamResponse> listMine(CampusAuthenticatedPrincipal principal) {
        String number = requireStudentNumber(principal);
        List<StudyTeamMemberEntity> own = memberMapper.selectList(
                new LambdaQueryWrapper<StudyTeamMemberEntity>()
                        .eq(StudyTeamMemberEntity::getStudentNumber, number)
                        .in(StudyTeamMemberEntity::getStatus,
                                StudyTeamMemberStatus.INVITED, StudyTeamMemberStatus.ACCEPTED)
                        .orderByDesc(StudyTeamMemberEntity::getCreatedAt)
                        .last("LIMIT 100"));
        List<StudyTeamResponse> result = new ArrayList<>();
        for (StudyTeamMemberEntity entry : own) {
            StudyTeamEntity team = teamMapper.selectById(entry.getTeamId());
            if (team != null) { result.add(response(team, null)); }
        }
        return List.copyOf(result);
    }

    private void acceptMember(StudyTeamMemberEntity member, CampusAuthenticatedPrincipal principal,
                              boolean allowRejoin) {
        if (member.getStatus() == StudyTeamMemberStatus.ACCEPTED
                && principal.userId().equals(member.getUserReference())) { return; }
        if (member.getStatus() == StudyTeamMemberStatus.LEFT
                || member.getStatus() == StudyTeamMemberStatus.DECLINED) {
            if (!allowRejoin) { throw conflict("team-invitation-not-active"); }
            member.setStatus(StudyTeamMemberStatus.INVITED);
            member.setUserReference(null);
            memberMapper.updateById(member);
        }
        if (member.getStatus() != StudyTeamMemberStatus.INVITED
                || memberMapper.acceptInvitation(member.getId(), principal.userId(), now()) != 1) {
            throw conflict("team-member-state-conflict");
        }
    }

    private StudyTeamEntity lockForming(Long teamId) {
        StudyTeamEntity team = teamMapper.lockById(teamId);
        if (team == null) { throw notFound("team-not-found"); }
        if (team.getStatus() != StudyTeamStatus.FORMING) { throw conflict("team-not-forming"); }
        return team;
    }

    private static void requireLeader(StudyTeamEntity team, CampusAuthenticatedPrincipal principal) {
        if (!team.getLeaderReference().equals(principal.userId())) { throw notFound("team-not-found"); }
    }

    private StudyTeamMemberEntity member(Long teamId, String number) {
        return memberMapper.selectOne(new LambdaQueryWrapper<StudyTeamMemberEntity>()
                .eq(StudyTeamMemberEntity::getTeamId, teamId)
                .eq(StudyTeamMemberEntity::getStudentNumber, number));
    }

    private List<StudyTeamMemberEntity> members(Long teamId) {
        return memberMapper.selectList(new LambdaQueryWrapper<StudyTeamMemberEntity>()
                .eq(StudyTeamMemberEntity::getTeamId, teamId)
                .orderByAsc(StudyTeamMemberEntity::getCreatedAt)
                .orderByAsc(StudyTeamMemberEntity::getId));
    }

    private void insertMember(Long teamId, String number, String userReference, StudyTeamMemberStatus status) {
        StudyTeamMemberEntity member = new StudyTeamMemberEntity();
        member.setTeamId(teamId);
        member.setStudentNumber(number);
        member.setUserReference(userReference);
        member.setStatus(status);
        if (status == StudyTeamMemberStatus.ACCEPTED) { member.setAcceptedAt(now()); }
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException exception) {
            throw conflict("team-member-already-present");
        }
    }

    private StudyTeamResponse response(StudyTeamEntity team, String inviteCode) {
        List<StudyTeamMemberEntity> memberRows = members(team.getId());
        String leaderNumber = memberRows.stream()
                .filter(member -> team.getLeaderReference().equals(member.getUserReference()))
                .map(StudyTeamMemberEntity::getStudentNumber)
                .findFirst().orElse(null);
        return new StudyTeamResponse(team.getId(), leaderNumber, team.getStatus(),
                memberRows.stream().map(member ->
                        new StudyTeamMemberResponse(member.getStudentNumber(), member.getStatus())).toList(),
                team.getInviteCodeExpiresAt(), inviteCode);
    }

    private static String requireStudentNumber(CampusAuthenticatedPrincipal principal) {
        if (principal == null || principal.studentNumber() == null || principal.studentNumber().isBlank()) {
            throw conflict("student-number-not-bound");
        }
        return principal.studentNumber();
    }

    private LocalDateTime now() { return LocalDateTime.now(reservationClock); }

    private static boolean validNumber(String number) {
        return number != null && number.matches("[0-9A-Za-z_-]{4,32}");
    }

    private static String newInviteCode() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static BusinessException badRequest(String code) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, code);
    }

    private static BusinessException conflict(String code) {
        return new BusinessException(HttpStatus.CONFLICT, code, code);
    }

    private static BusinessException notFound(String code) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, code);
    }
}
