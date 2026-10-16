package com.campusreserve.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dao.entity.*;
import com.campusreserve.reservation.dao.mapper.*;
import com.campusreserve.reservation.domain.*;
import com.campusreserve.reservation.dto.req.CreateStudyRoomBookingRequest;
import com.campusreserve.reservation.dto.req.CreateStudyRoomWindowRequest;
import com.campusreserve.reservation.dto.req.UpdateStudyRoomOccupancyRequest;
import com.campusreserve.reservation.dto.req.ReplaceStudyRoomWindowRequest;
import com.campusreserve.reservation.dto.resp.StudyRoomBookingResponse;
import com.campusreserve.reservation.dto.resp.StudyRoomAvailabilityResponse;
import com.campusreserve.reservation.dto.resp.StudyRoomOccupiedIntervalResponse;
import com.campusreserve.reservation.dto.resp.StudyRoomWindowResponse;
import com.campusreserve.reservation.manager.rate.StudyRoomAdmissionGuard;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import com.campusreserve.reservation.service.ReservationOutboxService;
import com.campusreserve.reservation.service.StudyRoomService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * All mutations of one room's schedule/occupancy serialize on reservable_resource.
 * Participant buckets additionally serialize cross-room bookings for one student.
 */
@Service
@RequiredArgsConstructor
public class StudyRoomServiceImpl implements StudyRoomService {
    private static final String SYSTEM_NO_SHOW_OPERATOR = "system-no-show";
    private final ReservableResourceMapper resourceMapper;
    private final StudyRoomOpenWindowMapper windowMapper;
    private final StudyRoomBookingMapper bookingMapper;
    private final StudyRoomBookingMemberMapper bookingMemberMapper;
    private final StudyRoomMemberBucketMapper bucketMapper;
    private final StudyTeamMapper teamMapper;
    private final StudyTeamMemberMapper teamMemberMapper;
    private final ReservationOutboxService outboxService;
    private final StudyRoomAdmissionGuard admissionGuard;
    private final PublicCatalogCacheInvalidation cacheInvalidation;
    private final Clock reservationClock;

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF','ADMIN')")
    public StudyRoomWindowResponse openWindow(CreateStudyRoomWindowRequest request) {
        validateWindow(request.startsAt(), request.endsAt());
        requireRoom(request.resourceId());
        if (windowMapper.countOverlapping(request.resourceId(), request.startsAt(), request.endsAt()) != 0) {
            throw conflict("study-room-window-overlap");
        }
        StudyRoomOpenWindowEntity window = new StudyRoomOpenWindowEntity();
        window.setResourceId(request.resourceId());
        window.setStartsAt(request.startsAt());
        window.setEndsAt(request.endsAt());
        window.setStatus(StudyRoomWindowStatus.OPEN);
        windowMapper.insert(window);
        cacheInvalidation.evictStudyRoomWindowsAfterCommit(request.resourceId());
        return windowResponse(window);
    }

    @Override
    @Cacheable(cacheNames = "study-room-windows", key = "#resourceId", sync = true)
    public List<StudyRoomWindowResponse> listWindows(Long resourceId) {
        ReservableResourceEntity resource = resourceMapper.selectById(resourceId);
        if (resource == null || !"STUDY_ROOM".equals(resource.getResourceType())
                || resource.getStatus() != ResourceStatus.PUBLISHED) {
            throw notFound("study-room-not-found");
        }
        LocalDate lastBookableDate = LocalDate.now(reservationClock).plusDays(2);
        return windowMapper.futureOpen(resourceId, now()).stream()
                .filter(window -> !window.getStartsAt().toLocalDate().isAfter(lastBookableDate))
                .map(this::windowResponse).toList();
    }

    @Override
    public StudyRoomAvailabilityResponse availability(Long resourceId, LocalDate date) {
        LocalDate today = LocalDate.now(reservationClock);
        if (date == null || date.isBefore(today) || date.isAfter(today.plusDays(2))) {
            throw badRequest("study-room-availability-date-out-of-range");
        }
        ReservableResourceEntity room = resourceMapper.selectById(resourceId);
        if (room == null || !"STUDY_ROOM".equals(room.getResourceType())
                || room.getStatus() != ResourceStatus.PUBLISHED) {
            throw notFound("study-room-not-found");
        }
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();
        var windows = windowMapper.selectList(new LambdaQueryWrapper<StudyRoomOpenWindowEntity>()
                .eq(StudyRoomOpenWindowEntity::getResourceId, resourceId)
                .eq(StudyRoomOpenWindowEntity::getStatus, StudyRoomWindowStatus.OPEN)
                .ge(StudyRoomOpenWindowEntity::getStartsAt, startOfDay)
                .lt(StudyRoomOpenWindowEntity::getStartsAt, endOfDay)
                .orderByAsc(StudyRoomOpenWindowEntity::getStartsAt))
                .stream().map(this::windowResponse).toList();
        var occupied = bookingMapper.selectList(new LambdaQueryWrapper<StudyRoomBookingEntity>()
                .eq(StudyRoomBookingEntity::getResourceId, resourceId)
                .in(StudyRoomBookingEntity::getStatus, StudyRoomBookingStatus.CONFIRMED,
                        StudyRoomBookingStatus.CHECKED_IN, StudyRoomBookingStatus.COMPLETED)
                .ge(StudyRoomBookingEntity::getStartsAt, startOfDay)
                .lt(StudyRoomBookingEntity::getStartsAt, endOfDay)
                .orderByAsc(StudyRoomBookingEntity::getStartsAt))
                .stream().map(booking -> new StudyRoomOccupiedIntervalResponse(
                        booking.getStartsAt(), booking.getEndsAt())).toList();
        return new StudyRoomAvailabilityResponse(
                resourceId.toString(), date, windows, occupied);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF','ADMIN')")
    public StudyRoomWindowResponse closeWindowForMaintenance(Long windowId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 512) {
            throw badRequest("maintenance-reason-required");
        }
        StudyRoomOpenWindowEntity initial = windowMapper.selectById(windowId);
        if (initial == null) { throw notFound("study-room-window-not-found"); }
        requireRoom(initial.getResourceId());
        StudyRoomOpenWindowEntity window = windowMapper.lockById(windowId);
        if (window == null || window.getStatus() != StudyRoomWindowStatus.OPEN) {
            throw conflict("study-room-window-not-open");
        }
        for (StudyRoomBookingEntity booking : activeInWindow(window)) {
            cancelForMaintenance(booking, reason.trim());
        }
        if (windowMapper.close(windowId) != 1) { throw conflict("study-room-window-state-conflict"); }
        cacheInvalidation.evictStudyRoomWindowsAfterCommit(window.getResourceId());
        window.setStatus(StudyRoomWindowStatus.CLOSED);
        return windowResponse(window);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF','ADMIN')")
    public StudyRoomWindowResponse shortenWindow(Long windowId, ReplaceStudyRoomWindowRequest request) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw badRequest("maintenance-reason-required");
        }
        validateWindow(request.startsAt(), request.endsAt());
        StudyRoomOpenWindowEntity initial = windowMapper.selectById(windowId);
        if (initial == null) { throw notFound("study-room-window-not-found"); }
        requireRoom(initial.getResourceId());
        StudyRoomOpenWindowEntity window = windowMapper.lockById(windowId);
        if (window == null || window.getStatus() != StudyRoomWindowStatus.OPEN) {
            throw conflict("study-room-window-not-open");
        }
        if (request.startsAt().isBefore(window.getStartsAt())
                || request.endsAt().isAfter(window.getEndsAt())) {
            throw badRequest("study-room-window-can-only-shorten");
        }
        if (windowMapper.countOtherOverlapping(window.getResourceId(), windowId,
                request.startsAt(), request.endsAt()) != 0) {
            throw conflict("study-room-window-overlap");
        }
        for (StudyRoomBookingEntity booking : activeInWindow(window)) {
            if (booking.getStartsAt().isBefore(request.startsAt())
                    || booking.getEndsAt().isAfter(request.endsAt())) {
                cancelForMaintenance(booking, request.reason().trim());
            }
        }
        if (windowMapper.replaceInterval(windowId, request.startsAt(), request.endsAt()) != 1) {
            throw conflict("study-room-window-state-conflict");
        }
        cacheInvalidation.evictStudyRoomWindowsAfterCommit(window.getResourceId());
        window.setStartsAt(request.startsAt());
        window.setEndsAt(request.endsAt());
        return windowResponse(window);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF','ADMIN')")
    public void updateOccupancy(Long resourceId, UpdateStudyRoomOccupancyRequest request) {
        if (request.minParticipants() > request.maxParticipants()) {
            throw badRequest("study-room-occupancy-invalid");
        }
        ReservableResourceEntity room = requireRoom(resourceId);
        long active = bookingMapper.selectCount(new LambdaQueryWrapper<StudyRoomBookingEntity>()
                .eq(StudyRoomBookingEntity::getResourceId, resourceId)
                .in(StudyRoomBookingEntity::getStatus,
                        StudyRoomBookingStatus.CONFIRMED, StudyRoomBookingStatus.WAITLISTED,
                        StudyRoomBookingStatus.CHECKED_IN, StudyRoomBookingStatus.COMPLETED)
                .gt(StudyRoomBookingEntity::getStartsAt, now()));
        if (active != 0) { throw conflict("study-room-has-active-bookings"); }
        room.setMinParticipants(request.minParticipants());
        room.setMaxParticipants(request.maxParticipants());
        if (resourceMapper.updateById(room) != 1) { throw conflict("resource-version-conflict"); }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("hasRole('STUDENT')")
    public StudyRoomBookingResponse book(CreateStudyRoomBookingRequest request, String key,
                                          CampusAuthenticatedPrincipal principal) {
        requireStudent(principal);
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,80}")) { throw badRequest("invalid-idempotency-key"); }
        StudyRoomBookingEntity replay = byKey(principal.userId(), key);
        if (replay != null) { return replay(request, replay); }
        validateBookingInterval(request.startsAt(), request.endsAt());
        // The limiter is optional and only sheds bursts. It cannot approve a
        // booking: room locks, membership and interval checks below still own it.
        admissionGuard.requireAdmission(request.resourceId(), request.startsAt().toLocalDate(),
                principal.userId());
        ReservableResourceEntity room = requireRoom(request.resourceId());
        StudyTeamEntity team = teamMapper.lockById(request.teamId());
        // A concurrent replay may have booked a different room while this
        // request waited for the team lock; inspect the key again before
        // interpreting BOOKED as a conflicting new request.
        replay = bookingMapper.findReplayFresh(principal.userId(), key);
        if (replay != null) { return replay(request, replay); }
        if (team == null || !principal.userId().equals(team.getLeaderReference())) {
            throw notFound("study-team-not-found");
        }
        if (team.getStatus() != StudyTeamStatus.FORMING) { throw conflict("study-team-already-booked"); }
        List<StudyTeamMemberEntity> accepted = teamMemberMapper.acceptedForUpdate(team.getId());
        if (accepted.size() < room.getMinParticipants() || accepted.size() > room.getMaxParticipants()
                || accepted.stream().anyMatch(member -> member.getUserReference() == null)) {
            throw conflict("study-team-size-or-consent-invalid");
        }
        if (windowMapper.containing(room.getId(), request.startsAt(), request.endsAt()) == null) {
            throw conflict("study-room-outside-open-window");
        }
        // A waitlisted team may have become eligible after a participant's
        // conflicting booking in another room was cancelled. Do not let a new
        // request jump ahead merely because no room mutation triggered it.
        promoteWaiting(room.getId());
        StudyRoomBookingEntity booking = new StudyRoomBookingEntity();
        booking.setBookingNo(UUID.randomUUID().toString().replace("-", ""));
        booking.setResourceId(room.getId());
        booking.setTeamId(team.getId());
        booking.setLeaderReference(principal.userId());
        booking.setIdempotencyKey(key);
        booking.setRequiredAttendanceCount(room.getMinParticipants());
        booking.setStartsAt(request.startsAt());
        booking.setEndsAt(request.endsAt());
        boolean roomFree = bookingMapper.findConfirmedOverlapForUpdate(room.getId(),
                request.startsAt(), request.endsAt()) == null;
        if (roomFree) {
            requireParticipantsFree(accepted, request.startsAt(), request.endsAt());
        }
        booking.setStatus(roomFree ? StudyRoomBookingStatus.CONFIRMED : StudyRoomBookingStatus.WAITLISTED);
        try {
            bookingMapper.insert(booking);
        } catch (DuplicateKeyException exception) {
            throw conflict("study-room-booking-idempotency-conflict");
        }
        for (StudyTeamMemberEntity member : accepted) {
            StudyRoomBookingMemberEntity snapshot = new StudyRoomBookingMemberEntity();
            snapshot.setBookingId(booking.getId());
            snapshot.setStudentNumber(member.getStudentNumber());
            snapshot.setUserReference(member.getUserReference());
            bookingMemberMapper.insert(snapshot);
        }
        if (roomFree) { claimBuckets(booking, participants(booking.getId())); }
        if (teamMapper.markBooked(team.getId()) != 1) { throw conflict("study-team-state-conflict"); }
        outboxService.recordStudyRoomStatusChanged(booking, participants(booking.getId()),
                roomFree ? ReservationEventType.STUDY_ROOM_CONFIRMED : ReservationEventType.STUDY_ROOM_WAITLISTED);
        return response(booking);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("hasRole('STUDENT')")
    public StudyRoomBookingResponse cancel(Long bookingId, CampusAuthenticatedPrincipal principal) {
        requireStudent(principal);
        StudyRoomBookingEntity initial = bookingMapper.selectById(bookingId);
        if (initial == null || !principal.userId().equals(initial.getLeaderReference())) {
            throw notFound("study-room-booking-not-found");
        }
        requireRoom(initial.getResourceId());
        StudyRoomBookingEntity booking = bookingMapper.lockById(bookingId);
        if (booking.getStatus() == StudyRoomBookingStatus.CANCELLED) { return response(booking); }
        boolean confirmed = booking.getStatus() == StudyRoomBookingStatus.CONFIRMED;
        if (bookingMapper.cancelByLeader(bookingId, principal.userId(), now()) != 1) {
            throw conflict("study-room-cancellation-window-closed");
        }
        bucketMapper.deleteByBooking(bookingId);
        if (teamMapper.reopenAfterCancellation(booking.getTeamId()) != 1) {
            throw conflict("study-team-state-conflict");
        }
        booking.setStatus(StudyRoomBookingStatus.CANCELLED);
        outboxService.recordStudyRoomStatusChanged(booking, participants(bookingId),
                ReservationEventType.STUDY_ROOM_CANCELLED);
        if (confirmed) { promoteWaiting(booking.getResourceId()); }
        return response(booking);
    }

    @Override
    @PreAuthorize("hasRole('STUDENT')")
    public StudyRoomBookingResponse get(Long bookingId, CampusAuthenticatedPrincipal principal) {
        requireStudent(principal);
        StudyRoomBookingEntity booking = bookingMapper.selectById(bookingId);
        if (booking == null || participants(bookingId).stream()
                .noneMatch(member -> principal.userId().equals(member.getUserReference()))) {
            throw notFound("study-room-booking-not-found");
        }
        return response(booking);
    }

    @Override
    @PreAuthorize("hasRole('STUDENT')")
    public List<StudyRoomBookingResponse> listMine(CampusAuthenticatedPrincipal principal) {
        requireStudent(principal);
        return bookingMapper.listForMember(principal.userId()).stream().map(this::response).toList();
    }

    @Override
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF','ADMIN')")
    public List<StudyRoomBookingResponse> workQueue() {
        return bookingMapper.workQueue(now()).stream().map(this::response).toList();
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF','ADMIN')")
    public StudyRoomBookingResponse checkIn(Long bookingId, String operator, String studentNumber) {
        if (studentNumber == null || studentNumber.isBlank()) {
            throw badRequest("study-room-check-in-member-required");
        }
        StudyRoomBookingEntity initial = requireBooking(bookingId);
        ReservableResourceEntity room = requireRoom(initial.getResourceId());
        StudyRoomBookingEntity booking = bookingMapper.lockById(bookingId);
        if (booking.getStatus() != StudyRoomBookingStatus.CONFIRMED
                && booking.getStatus() != StudyRoomBookingStatus.CHECKED_IN) {
            throw conflict("study-room-check-in-state-conflict");
        }
        List<StudyRoomBookingMemberEntity> members = participants(bookingId);
        StudyRoomBookingMemberEntity arriving = members.stream()
                .filter(member -> member.getStudentNumber().equals(studentNumber))
                .findFirst().orElseThrow(() -> conflict("study-room-check-in-member-not-in-booking"));
        if (arriving.getCheckedInAt() != null) { return response(booking); }
        LocalDateTime checkedInAt = now();
        if (checkedInAt.isBefore(booking.getStartsAt()) || !checkedInAt.isBefore(booking.getEndsAt())) {
            throw conflict("study-room-check-in-window-closed");
        }
        // The minimum must arrive before the grace deadline; once reached,
        // further booked members may still be recorded until the interval ends.
        if (booking.getStatus() == StudyRoomBookingStatus.CONFIRMED
                && !checkedInAt.isBefore(booking.getStartsAt().plusMinutes(room.getNoShowGraceMinutes()))) {
            throw conflict("study-room-check-in-grace-expired");
        }
        if (bookingMemberMapper.markArrived(bookingId, studentNumber, operator, checkedInAt) != 1) {
            throw conflict("study-room-check-in-member-state-conflict");
        }
        long arrivedCount = bookingMemberMapper.countArrived(bookingId);
        if (booking.getStatus() == StudyRoomBookingStatus.CONFIRMED
                && arrivedCount >= booking.getRequiredAttendanceCount()) {
            if (bookingMapper.checkIn(bookingId, operator, studentNumber, checkedInAt) != 1) {
                throw conflict("study-room-check-in-state-conflict");
            }
            booking = bookingMapper.lockById(bookingId);
            outboxService.recordStudyRoomStatusChanged(booking, members, ReservationEventType.STUDY_ROOM_CHECKED_IN);
        }
        return response(booking);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF','ADMIN')")
    public StudyRoomBookingResponse complete(Long bookingId, String operator) {
        StudyRoomBookingEntity initial = requireBooking(bookingId);
        requireRoom(initial.getResourceId());
        StudyRoomBookingEntity booking = bookingMapper.lockById(bookingId);
        if (booking.getStatus() == StudyRoomBookingStatus.COMPLETED
                && operator.equals(booking.getHandledByReference())) { return response(booking); }
        if (bookingMapper.complete(bookingId, operator, now()) != 1) {
            throw conflict("study-room-complete-state-conflict");
        }
        booking = bookingMapper.lockById(bookingId);
        outboxService.recordStudyRoomStatusChanged(booking, participants(bookingId),
                ReservationEventType.STUDY_ROOM_COMPLETED);
        return response(booking);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF','ADMIN')")
    public StudyRoomBookingResponse markNoShow(Long bookingId, String operator) {
        StudyRoomBookingEntity initial = requireBooking(bookingId);
        ReservableResourceEntity room = requireRoom(initial.getResourceId());
        StudyRoomBookingEntity booking = bookingMapper.lockById(bookingId);
        if (booking.getStatus() == StudyRoomBookingStatus.NO_SHOW
                && operator.equals(booking.getHandledByReference())) { return response(booking); }
        if (now().isBefore(booking.getStartsAt().plusMinutes(room.getNoShowGraceMinutes()))) {
            throw conflict("study-room-no-show-window-not-open");
        }
        if (bookingMapper.markNoShow(bookingId, operator, now()) != 1) {
            throw conflict("study-room-no-show-state-conflict");
        }
        bucketMapper.deleteByBooking(bookingId);
        booking = bookingMapper.lockById(bookingId);
        outboxService.recordStudyRoomStatusChanged(booking, participants(bookingId),
                ReservationEventType.STUDY_ROOM_NO_SHOW);
        promoteWaiting(booking.getResourceId());
        return response(booking);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int autoMarkNoShows(int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("no-show batch size must be between 1 and 500");
        }
        int markedCount = 0;
        for (StudyRoomBookingEntity booking : bookingMapper.lockDueNoShows(now(), batchSize)) {
            LocalDateTime markedAt = now();
            if (bookingMapper.markNoShow(booking.getId(), SYSTEM_NO_SHOW_OPERATOR, markedAt) != 1) {
                continue;
            }
            bucketMapper.deleteByBooking(booking.getId());
            booking.setStatus(StudyRoomBookingStatus.NO_SHOW);
            booking.setHandledByReference(SYSTEM_NO_SHOW_OPERATOR);
            booking.setNoShowAt(markedAt);
            outboxService.recordStudyRoomStatusChanged(booking, participants(booking.getId()),
                    ReservationEventType.STUDY_ROOM_NO_SHOW);
            promoteWaiting(booking.getResourceId());
            markedCount++;
        }
        return markedCount;
    }

    private void promoteWaiting(Long resourceId) {
        // Keep the existing ordered, locking queue read and per-interval current reads.
        // Only eliminate repeated reads; do not cap/skip the FIFO queue.
        Map<BookingInterval, Boolean> initiallyFree = new HashMap<>();
        List<StudyRoomBookingEntity> eligible = new ArrayList<>();
        for (StudyRoomBookingEntity candidate : bookingMapper.waiting(resourceId, now())) {
            BookingInterval interval = interval(candidate);
            if (initiallyFree.computeIfAbsent(interval, key ->
                    windowMapper.containing(resourceId, key.startsAt(), key.endsAt()) != null
                    && bookingMapper.findConfirmedOverlapForUpdate(resourceId, key.startsAt(), key.endsAt()) == null)) {
                eligible.add(candidate);
            }
        }
        if (eligible.isEmpty()) { return; }
        Map<Long, List<StudyRoomBookingMemberEntity>> memberRows = new HashMap<>();
        Set<Long> conflicts = new HashSet<>();
        // Bound SQL parameter lists while retaining ALL candidates in the original FIFO order.
        for (int offset = 0; offset < eligible.size(); offset += 200) {
            List<Long> ids = eligible.subList(offset, Math.min(offset + 200, eligible.size()))
                    .stream().map(StudyRoomBookingEntity::getId).toList();
            memberRows.putAll(bookingMemberMapper.forBookings(ids).stream()
                    .collect(Collectors.groupingBy(StudyRoomBookingMemberEntity::getBookingId)));
            conflicts.addAll(bucketMapper.conflictingBookings(ids));
        }
        List<BookingInterval> promotedIntervals = new ArrayList<>();
        Map<String, List<BookingInterval>> promotedMembers = new HashMap<>();
        for (StudyRoomBookingEntity candidate : eligible) {
            BookingInterval interval = interval(candidate);
            List<StudyRoomBookingMemberEntity> members = memberRows.getOrDefault(candidate.getId(), List.of());
            if (members.isEmpty()) { throw conflict("study-room-booking-members-missing"); }
            if (conflicts.contains(candidate.getId()) || promotedIntervals.stream().anyMatch(interval::overlaps)
                    || members.stream().anyMatch(member -> promotedMembers
                    .getOrDefault(member.getStudentNumber(), List.of()).stream().anyMatch(interval::overlaps))) { continue; }
            // A concurrent booking in another room can still win after the bulk read.
            // Keep the original unique-key claim and whole-transaction rollback semantics.
            claimBuckets(candidate, members);
            if (bookingMapper.promote(candidate.getId()) != 1) { throw conflict("study-room-promotion-conflict"); }
            candidate.setStatus(StudyRoomBookingStatus.CONFIRMED);
            outboxService.recordStudyRoomStatusChanged(candidate, members,
                    ReservationEventType.STUDY_ROOM_PROMOTED);
            promotedIntervals.add(interval);
            for (StudyRoomBookingMemberEntity member : members) {
                promotedMembers.computeIfAbsent(member.getStudentNumber(), key -> new ArrayList<>()).add(interval);
            }
        }
    }

    private record BookingInterval(LocalDateTime startsAt, LocalDateTime endsAt) {
        boolean overlaps(BookingInterval other) {
            return startsAt.isBefore(other.endsAt) && endsAt.isAfter(other.startsAt);
        }
    }

    private static BookingInterval interval(StudyRoomBookingEntity booking) {
        return new BookingInterval(booking.getStartsAt(), booking.getEndsAt());
    }

    private List<StudyRoomBookingEntity> activeInWindow(StudyRoomOpenWindowEntity window) {
        return bookingMapper.activeInWindowForUpdate(
                window.getResourceId(), window.getStartsAt(), window.getEndsAt());
    }

    private void cancelForMaintenance(StudyRoomBookingEntity booking, String reason) {
        if (!booking.getStartsAt().isAfter(now())) {
            throw conflict("study-room-window-has-in-use-booking");
        }
        if (bookingMapper.cancelByAdmin(booking.getId(), reason) != 1) {
            throw conflict("study-room-booking-state-conflict");
        }
        bucketMapper.deleteByBooking(booking.getId());
        if (teamMapper.reopenAfterCancellation(booking.getTeamId()) != 1) {
            throw conflict("study-team-state-conflict");
        }
        booking.setStatus(StudyRoomBookingStatus.CANCELLED_BY_ADMIN);
        outboxService.recordStudyRoomStatusChanged(booking, participants(booking.getId()),
                ReservationEventType.STUDY_ROOM_CANCELLED_BY_ADMIN);
    }

    private void claimBuckets(StudyRoomBookingEntity booking, List<StudyRoomBookingMemberEntity> members) {
        for (StudyRoomBookingMemberEntity member : members) {
            for (LocalDateTime bucket = booking.getStartsAt(); bucket.isBefore(booking.getEndsAt());
                 bucket = bucket.plusMinutes(30)) {
                try {
                    bucketMapper.insertBucket(member.getStudentNumber(), bucket, booking.getId());
                } catch (DuplicateKeyException exception) {
                    // Cross-room concurrency: the unique key, not a stale availability read, wins.
                    throw conflict("study-room-participant-time-conflict");
                }
            }
        }
    }

    private void requireParticipantsFree(List<StudyTeamMemberEntity> members,
                                         LocalDateTime startsAt, LocalDateTime endsAt) {
        if (members.stream().anyMatch(member -> bucketMapper.countMemberOverlap(
                member.getStudentNumber(), startsAt, endsAt) != 0)) {
            throw conflict("study-room-participant-time-conflict");
        }
    }

    private StudyRoomBookingEntity byKey(String leader, String key) {
        return bookingMapper.selectOne(new LambdaQueryWrapper<StudyRoomBookingEntity>()
                .eq(StudyRoomBookingEntity::getLeaderReference, leader)
                .eq(StudyRoomBookingEntity::getIdempotencyKey, key));
    }

    private StudyRoomBookingResponse replay(CreateStudyRoomBookingRequest request, StudyRoomBookingEntity existing) {
        if (!existing.getResourceId().equals(request.resourceId())
                || !existing.getTeamId().equals(request.teamId())
                || !existing.getStartsAt().equals(request.startsAt())
                || !existing.getEndsAt().equals(request.endsAt())) {
            throw conflict("study-room-idempotency-key-reused");
        }
        return response(existing);
    }

    private ReservableResourceEntity requireRoom(Long resourceId) {
        ReservableResourceEntity room = resourceMapper.lockById(resourceId);
        if (room == null || !"STUDY_ROOM".equals(room.getResourceType())
                || room.getStatus() != ResourceStatus.PUBLISHED) {
            throw notFound("study-room-not-found");
        }
        return room;
    }

    private List<StudyRoomBookingMemberEntity> participants(Long bookingId) {
        return bookingMemberMapper.selectList(new LambdaQueryWrapper<StudyRoomBookingMemberEntity>()
                .eq(StudyRoomBookingMemberEntity::getBookingId, bookingId)
                .orderByAsc(StudyRoomBookingMemberEntity::getId));
    }

    private StudyRoomBookingResponse response(StudyRoomBookingEntity booking) {
        List<StudyRoomBookingMemberEntity> memberRows = participants(booking.getId());
        String leaderNumber = memberRows.stream()
                .filter(member -> booking.getLeaderReference().equals(member.getUserReference()))
                .map(StudyRoomBookingMemberEntity::getStudentNumber)
                .findFirst().orElse(null);
        return new StudyRoomBookingResponse(booking.getId().toString(), booking.getBookingNo(),
                booking.getResourceId().toString(), booking.getTeamId().toString(),
                booking.getStartsAt(), booking.getEndsAt(), booking.getStatus(),
                memberRows.stream().map(StudyRoomBookingMemberEntity::getStudentNumber).toList(),
                leaderNumber,
                booking.getCancellationReason(), booking.getHandledByReference(),
                booking.getCheckedInStudentNumber(), booking.getCheckedInAt(),
                booking.getCompletedAt(), booking.getNoShowAt(),
                memberRows.stream().filter(member -> member.getCheckedInAt() != null)
                        .map(StudyRoomBookingMemberEntity::getStudentNumber).toList(),
                memberRows.stream().filter(member -> member.getCheckedInAt() != null).count(),
                booking.getRequiredAttendanceCount());
    }

    private StudyRoomBookingEntity requireBooking(Long id) {
        StudyRoomBookingEntity booking = bookingMapper.selectById(id);
        if (booking == null) { throw notFound("study-room-booking-not-found"); }
        return booking;
    }

    private StudyRoomWindowResponse windowResponse(StudyRoomOpenWindowEntity window) {
        return new StudyRoomWindowResponse(window.getId().toString(), window.getResourceId().toString(),
                window.getStartsAt(), window.getEndsAt(), window.getStatus());
    }

    private void validateWindow(LocalDateTime startsAt, LocalDateTime endsAt) {
        if (!validDateTime(startsAt) || !validDateTime(endsAt) || !startsAt.toLocalDate().equals(endsAt.toLocalDate())
                || !startsAt.isBefore(endsAt) || !startsAt.isAfter(now())) {
            throw badRequest("study-room-window-invalid");
        }
    }

    private void validateBookingInterval(LocalDateTime startsAt, LocalDateTime endsAt) {
        if (!validDateTime(startsAt) || !validDateTime(endsAt)
                || !startsAt.toLocalDate().equals(endsAt.toLocalDate()) || !startsAt.isBefore(endsAt)) {
            throw badRequest("study-room-interval-invalid");
        }
        long minutes = Duration.between(startsAt, endsAt).toMinutes();
        if (minutes < 60 || minutes > 240 || !startsAt.isAfter(now())) {
            throw conflict("study-room-interval-unbookable");
        }
        if (now().isBefore(startsAt.toLocalDate().minusDays(2).atStartOfDay())) {
            throw conflict("study-room-booking-not-open-yet");
        }
    }

    private static boolean validDateTime(LocalDateTime value) {
        return value != null && value.getMinute() % 30 == 0 && value.getSecond() == 0
                && value.getNano() == 0;
    }

    private static void requireStudent(CampusAuthenticatedPrincipal principal) {
        if (principal == null || principal.studentNumber() == null || principal.studentNumber().isBlank()) {
            throw conflict("student-number-not-bound");
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(reservationClock); }

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
