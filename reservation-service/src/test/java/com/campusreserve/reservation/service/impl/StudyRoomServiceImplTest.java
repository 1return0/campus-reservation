package com.campusreserve.reservation.service.impl;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dao.entity.ReservableResourceEntity;
import com.campusreserve.reservation.dao.entity.StudyRoomBookingEntity;
import com.campusreserve.reservation.dao.entity.StudyRoomBookingMemberEntity;
import com.campusreserve.reservation.dao.mapper.*;
import com.campusreserve.reservation.domain.ResourceStatus;
import com.campusreserve.reservation.domain.ReservationEventType;
import com.campusreserve.reservation.domain.StudyRoomBookingStatus;
import com.campusreserve.reservation.dto.req.CreateStudyRoomBookingRequest;
import com.campusreserve.reservation.dto.req.CreateStudyRoomWindowRequest;
import com.campusreserve.reservation.manager.rate.StudyRoomAdmissionGuard;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import com.campusreserve.reservation.service.ReservationOutboxService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StudyRoomServiceImplTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"),
            ZoneId.of("Asia/Shanghai"));
    private final ReservableResourceMapper resourceMapper = mock(ReservableResourceMapper.class);
    private final StudyRoomOpenWindowMapper windowMapper = mock(StudyRoomOpenWindowMapper.class);
    private final StudyRoomBookingMapper bookingMapper = mock(StudyRoomBookingMapper.class);
    private final StudyRoomBookingMemberMapper bookingMemberMapper = mock(StudyRoomBookingMemberMapper.class);
    private final ReservationOutboxService outboxService = mock(ReservationOutboxService.class);
    private final StudyRoomAdmissionGuard admissionGuard = mock(StudyRoomAdmissionGuard.class);
    private final PublicCatalogCacheInvalidation cacheInvalidation = mock(PublicCatalogCacheInvalidation.class);
    private final StudyRoomServiceImpl service = new StudyRoomServiceImpl(
            resourceMapper, windowMapper, bookingMapper, bookingMemberMapper,
            mock(StudyRoomMemberBucketMapper.class), mock(StudyTeamMapper.class),
            mock(StudyTeamMemberMapper.class), outboxService, admissionGuard,
            cacheInvalidation, CLOCK);
    private final CampusAuthenticatedPrincipal student = new CampusAuthenticatedPrincipal(
            "user-1", Set.of("STUDENT"), "20260001");

    @Test
    void d_minus_two_is_calendar_midnight_not_a_rolling_48_hour_window() {
        // It is 08:00 on October 8: October 11 remains closed, October 10 is open.
        assertThatThrownBy(() -> service.book(new CreateStudyRoomBookingRequest(1L, 2L,
                LocalDateTime.of(2026, 10, 11, 7, 30),
                LocalDateTime.of(2026, 10, 11, 8, 30)), "request123", student))
                .isInstanceOf(BusinessException.class).hasMessage("study-room-booking-not-open-yet");
        verify(resourceMapper, never()).lockById(any());
    }

    @Test
    void booking_release_changes_at_campus_midnight() {
        var request = new CreateStudyRoomBookingRequest(1L, 2L,
                LocalDateTime.of(2026, 10, 10, 7, 30),
                LocalDateTime.of(2026, 10, 10, 8, 30));
        var beforeMidnight = new StudyRoomServiceImpl(resourceMapper, windowMapper, bookingMapper,
                mock(StudyRoomBookingMemberMapper.class), mock(StudyRoomMemberBucketMapper.class),
                mock(StudyTeamMapper.class), mock(StudyTeamMemberMapper.class),
                mock(ReservationOutboxService.class), admissionGuard, cacheInvalidation,
                Clock.fixed(Instant.parse("2026-10-07T15:59:00Z"), ZoneId.of("Asia/Shanghai")));
        assertThatThrownBy(() -> beforeMidnight.book(request, "request123", student))
                .hasMessage("study-room-booking-not-open-yet");
        verify(resourceMapper, never()).lockById(any());

        var atMidnight = new StudyRoomServiceImpl(resourceMapper, windowMapper, bookingMapper,
                mock(StudyRoomBookingMemberMapper.class), mock(StudyRoomMemberBucketMapper.class),
                mock(StudyTeamMapper.class), mock(StudyTeamMemberMapper.class),
                mock(ReservationOutboxService.class), admissionGuard, cacheInvalidation,
                Clock.fixed(Instant.parse("2026-10-07T16:00:00Z"), ZoneId.of("Asia/Shanghai")));
        assertThatThrownBy(() -> atMidnight.book(request, "request123", student))
                .hasMessage("study-room-not-found");
        verify(admissionGuard).requireAdmission(1L, request.startsAt().toLocalDate(), "user-1");
        verify(resourceMapper).lockById(1L);
    }

    @Test
    void redis_rejection_stops_before_room_lock_and_overlap_queries() {
        var request = new CreateStudyRoomBookingRequest(1L, 2L,
                LocalDateTime.of(2026, 10, 10, 7, 30),
                LocalDateTime.of(2026, 10, 10, 8, 30));
        doThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                "study-room-admission-rate-limited", "study-room-admission-rate-limited"))
                .when(admissionGuard).requireAdmission(1L, request.startsAt().toLocalDate(), "user-1");

        assertThatThrownBy(() -> service.book(request, "request123", student))
                .isInstanceOf(BusinessException.class).hasMessage("study-room-admission-rate-limited");
        verify(resourceMapper, never()).lockById(any());
        verifyNoInteractions(windowMapper);
    }

    @Test
    void rejects_duration_outside_one_to_four_hours_and_off_grid_times() {
        assertThatThrownBy(() -> service.book(new CreateStudyRoomBookingRequest(1L, 2L,
                LocalDateTime.of(2026, 10, 10, 7, 30),
                LocalDateTime.of(2026, 10, 10, 12, 0)), "request123", student))
                .isInstanceOf(BusinessException.class).hasMessage("study-room-interval-unbookable");
        assertThatThrownBy(() -> service.book(new CreateStudyRoomBookingRequest(1L, 2L,
                LocalDateTime.of(2026, 10, 10, 7, 45),
                LocalDateTime.of(2026, 10, 10, 8, 45)), "request123", student))
                .isInstanceOf(BusinessException.class).hasMessage("study-room-interval-invalid");
    }

    @Test
    void rejects_overlapping_room_windows_under_room_lock() {
        ReservableResourceEntity room = new ReservableResourceEntity();
        room.setId(1L);
        room.setResourceType("STUDY_ROOM");
        room.setStatus(ResourceStatus.PUBLISHED);
        when(resourceMapper.lockById(1L)).thenReturn(room);
        LocalDateTime start = LocalDateTime.of(2026, 10, 10, 7, 30);
        LocalDateTime end = LocalDateTime.of(2026, 10, 10, 21, 30);
        when(windowMapper.countOverlapping(1L, start, end)).thenReturn(1L);
        assertThatThrownBy(() -> service.openWindow(new CreateStudyRoomWindowRequest(1L, start, end)))
                .isInstanceOf(BusinessException.class).hasMessage("study-room-window-overlap");
        verify(resourceMapper).lockById(1L);
    }

    @Test
    void each_member_is_recorded_but_only_the_third_arrival_checks_in_the_booking() {
        StudyRoomBookingEntity booking = confirmedBooking();
        List<StudyRoomBookingMemberEntity> members = List.of(
                member("20260001"), member("20260002"), member("20260003"));
        stubAttendance(booking, members);
        StudyRoomServiceImpl atFivePast = at(LocalDateTime.of(2026, 10, 8, 14, 5));

        var first = atFivePast.checkIn(50L, "staff-1", "20260001");
        assertThat(first.status()).isEqualTo(StudyRoomBookingStatus.CONFIRMED);
        assertThat(first.checkedInCount()).isEqualTo(1);
        assertThat(first.requiredAttendanceCount()).isEqualTo(3);
        verify(bookingMapper, never()).checkIn(anyLong(), anyString(), anyString(), any());
        verifyNoInteractions(outboxService);

        var second = atFivePast.checkIn(50L, "staff-1", "20260002");
        assertThat(second.status()).isEqualTo(StudyRoomBookingStatus.CONFIRMED);
        assertThat(second.checkedInCount()).isEqualTo(2);
        assertThatThrownBy(() -> atFivePast.complete(50L, "staff-1"))
                .hasMessage("study-room-complete-state-conflict");

        var third = atFivePast.checkIn(50L, "staff-1", "20260003");
        assertThat(third.status()).isEqualTo(StudyRoomBookingStatus.CHECKED_IN);
        assertThat(third.checkedInStudentNumbers())
                .containsExactly("20260001", "20260002", "20260003");
        verify(bookingMapper, times(1)).checkIn(eq(50L), eq("staff-1"), eq("20260003"), any());
        verify(outboxService, times(1)).recordStudyRoomStatusChanged(
                eq(booking), eq(members), eq(ReservationEventType.STUDY_ROOM_CHECKED_IN));

        var replay = atFivePast.checkIn(50L, "staff-1", "20260003");
        assertThat(replay.checkedInCount()).isEqualTo(3);
        verify(bookingMemberMapper, times(3)).markArrived(eq(50L), anyString(), eq("staff-1"), any());
        verify(outboxService, times(1)).recordStudyRoomStatusChanged(
                eq(booking), eq(members), eq(ReservationEventType.STUDY_ROOM_CHECKED_IN));
    }

    @Test
    void unbooked_student_cannot_check_in_and_incomplete_team_cannot_arrive_after_grace() {
        StudyRoomBookingEntity booking = confirmedBooking();
        stubAttendance(booking, List.of(member("20260001"), member("20260002"), member("20260003")));
        StudyRoomServiceImpl atFivePast = at(LocalDateTime.of(2026, 10, 8, 14, 5));
        assertThatThrownBy(() -> atFivePast.checkIn(50L, "staff-1", "20269999"))
                .hasMessage("study-room-check-in-member-not-in-booking");

        StudyRoomServiceImpl atGrace = at(LocalDateTime.of(2026, 10, 8, 14, 15));
        assertThatThrownBy(() -> atGrace.checkIn(50L, "staff-1", "20260001"))
                .hasMessage("study-room-check-in-grace-expired");
        verify(bookingMemberMapper, never()).markArrived(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void automatic_no_show_releases_member_buckets_and_records_one_booking_event() {
        StudyRoomBookingEntity booking = confirmedBooking();
        List<StudyRoomBookingMemberEntity> members = List.of(member("20260001"));
        StudyRoomMemberBucketMapper bucketMapper = mock(StudyRoomMemberBucketMapper.class);
        StudyRoomServiceImpl automatic = new StudyRoomServiceImpl(
                resourceMapper, windowMapper, bookingMapper, bookingMemberMapper, bucketMapper,
                mock(StudyTeamMapper.class), mock(StudyTeamMemberMapper.class), outboxService,
                admissionGuard, cacheInvalidation, CLOCK);
        when(bookingMapper.lockDueNoShows(any(), eq(10))).thenReturn(List.of(booking));
        when(bookingMapper.markNoShow(eq(50L), eq("system-no-show"), any())).thenReturn(1);
        when(bookingMapper.waiting(eq(1L), any())).thenReturn(List.of());
        when(bookingMemberMapper.selectList(any())).thenReturn(members);

        assertThat(automatic.autoMarkNoShows(10)).isEqualTo(1);
        assertThat(booking.getStatus()).isEqualTo(StudyRoomBookingStatus.NO_SHOW);
        verify(bucketMapper).deleteByBooking(50L);
        verify(outboxService).recordStudyRoomStatusChanged(
                booking, members, ReservationEventType.STUDY_ROOM_NO_SHOW);
    }

    private StudyRoomServiceImpl at(LocalDateTime localNow) {
        return new StudyRoomServiceImpl(resourceMapper, windowMapper, bookingMapper,
                bookingMemberMapper, mock(StudyRoomMemberBucketMapper.class), mock(StudyTeamMapper.class),
                mock(StudyTeamMemberMapper.class), outboxService, admissionGuard, cacheInvalidation,
                Clock.fixed(localNow.atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                        ZoneId.of("Asia/Shanghai")));
    }

    private StudyRoomBookingEntity confirmedBooking() {
        StudyRoomBookingEntity booking = new StudyRoomBookingEntity();
        booking.setId(50L);
        booking.setResourceId(1L);
        booking.setTeamId(2L);
        booking.setLeaderReference("user-1");
        booking.setBookingNo("booking-50");
        booking.setStatus(StudyRoomBookingStatus.CONFIRMED);
        booking.setRequiredAttendanceCount(3);
        booking.setStartsAt(LocalDateTime.of(2026, 10, 8, 14, 0));
        booking.setEndsAt(LocalDateTime.of(2026, 10, 8, 16, 0));
        ReservableResourceEntity room = new ReservableResourceEntity();
        room.setId(1L);
        room.setResourceType("STUDY_ROOM");
        room.setStatus(ResourceStatus.PUBLISHED);
        room.setNoShowGraceMinutes(15);
        when(resourceMapper.lockById(1L)).thenReturn(room);
        when(bookingMapper.selectById(50L)).thenReturn(booking);
        when(bookingMapper.lockById(50L)).thenReturn(booking);
        return booking;
    }

    private static StudyRoomBookingMemberEntity member(String studentNumber) {
        StudyRoomBookingMemberEntity member = new StudyRoomBookingMemberEntity();
        member.setBookingId(50L);
        member.setStudentNumber(studentNumber);
        member.setUserReference("user-" + studentNumber);
        return member;
    }

    private void stubAttendance(StudyRoomBookingEntity booking, List<StudyRoomBookingMemberEntity> members) {
        when(bookingMemberMapper.selectList(any())).thenReturn(members);
        when(bookingMemberMapper.markArrived(eq(50L), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String studentNumber = invocation.getArgument(1);
                    members.stream().filter(member -> member.getStudentNumber().equals(studentNumber))
                            .findFirst().orElseThrow().setCheckedInAt(invocation.getArgument(3));
                    return 1;
                });
        when(bookingMemberMapper.countArrived(50L)).thenAnswer(invocation ->
                members.stream().filter(member -> member.getCheckedInAt() != null).count());
        when(bookingMapper.checkIn(eq(50L), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    booking.setStatus(StudyRoomBookingStatus.CHECKED_IN);
                    booking.setHandledByReference(invocation.getArgument(1));
                    booking.setCheckedInStudentNumber(invocation.getArgument(2));
                    booking.setCheckedInAt(invocation.getArgument(3));
                    return 1;
                });
    }
}
