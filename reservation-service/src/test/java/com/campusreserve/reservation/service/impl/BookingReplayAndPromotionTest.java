package com.campusreserve.reservation.service.impl;

import com.campusreserve.reservation.dao.entity.*;
import com.campusreserve.reservation.dao.mapper.*;
import com.campusreserve.reservation.domain.*;
import com.campusreserve.reservation.dto.req.CreateStudyRoomBookingRequest;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import com.campusreserve.reservation.manager.rate.*;
import com.campusreserve.reservation.service.ReservationOutboxService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingReplayAndPromotionTest {
    final ReservableResourceMapper resources=mock(ReservableResourceMapper.class);
    final StudyRoomOpenWindowMapper windows=mock(StudyRoomOpenWindowMapper.class);
    final StudyRoomBookingMapper bookings=mock(StudyRoomBookingMapper.class);
    final StudyRoomBookingMemberMapper members=mock(StudyRoomBookingMemberMapper.class);
    final StudyRoomMemberBucketMapper buckets=mock(StudyRoomMemberBucketMapper.class);
    final StudyTeamMapper teams=mock(StudyTeamMapper.class);
    final ReservationOutboxService outbox=mock(ReservationOutboxService.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"),ZoneId.of("Asia/Shanghai"));
    final StudyRoomServiceImpl service=new StudyRoomServiceImpl(resources,windows,bookings,members,buckets,teams,
            mock(StudyTeamMemberMapper.class),outbox,mock(StudyRoomAdmissionGuard.class),
            mock(PublicCatalogCacheInvalidation.class),clock);

    StudyRoomBookingEntity booking(long id,int start,int end) {
        var b=new StudyRoomBookingEntity(); b.setId(id);b.setResourceId(1L);b.setTeamId(2L);
        b.setStartsAt(LocalDateTime.of(2026,10,9,start,0));b.setEndsAt(LocalDateTime.of(2026,10,9,end,0));
        b.setStatus(StudyRoomBookingStatus.WAITLISTED);b.setLeaderReference("student-1");b.setRequiredAttendanceCount(1);return b;
    }
    StudyRoomBookingMemberEntity member(long id) {
        var m=new StudyRoomBookingMemberEntity();m.setBookingId(id);m.setStudentNumber("S"+id);m.setUserReference("u"+id);return m;
    }
    void queue(List<StudyRoomBookingEntity> candidates) {
        when(bookings.waiting(eq(1L),any())).thenReturn(candidates);
        when(windows.containing(eq(1L),any(),any())).thenReturn(new StudyRoomOpenWindowEntity());
        when(bookings.findConfirmedOverlapForUpdate(eq(1L),any(),any())).thenReturn(null);
        when(members.forBookings(anyList())).thenAnswer(i->i.<List<Long>>getArgument(0).stream().map(this::member).toList());
        when(bookings.promote(anyLong())).thenReturn(1);
    }
    void promote() { ReflectionTestUtils.invokeMethod(service,"promoteWaiting",1L); }

    @Test void five_hundred_conflicts_are_batched_without_per_member_sql_or_queue_truncation() {
        queue(IntStream.rangeClosed(1,500).mapToObj(i->booking(i,14,16)).toList());
        when(buckets.conflictingBookings(anyList())).thenAnswer(i->i.getArgument(0));
        promote();
        verify(members,times(3)).forBookings(anyList());verify(buckets,times(3)).conflictingBookings(anyList());
        verify(windows,times(1)).containing(eq(1L),any(),any());
        verify(bookings,times(1)).findConfirmedOverlapForUpdate(eq(1L),any(),any());
        verify(buckets,never()).countMemberOverlap(anyString(),any(),any());
        verifyNoInteractions(outbox);
    }

    @Test void fifo_skips_ineligible_head_and_updates_room_occupancy_after_each_promotion() {
        var a=booking(1,14,16);var b=booking(2,14,16);var c=booking(3,15,17);var d=booking(4,16,17);
        queue(List.of(a,b,c,d));when(buckets.conflictingBookings(anyList())).thenReturn(List.of(1L));
        promote();
        var order=inOrder(outbox);
        order.verify(outbox).recordStudyRoomStatusChanged(eq(b),anyList(),eq(ReservationEventType.STUDY_ROOM_PROMOTED));
        order.verify(outbox).recordStudyRoomStatusChanged(eq(d),anyList(),eq(ReservationEventType.STUDY_ROOM_PROMOTED));
        verify(bookings,never()).promote(1L);verify(bookings,never()).promote(3L);
        assertThat(c.getStatus()).isEqualTo(StudyRoomBookingStatus.WAITLISTED);
    }

    @Test void cross_room_unique_bucket_conflict_is_not_swallowed() {
        queue(List.of(booking(1,14,16)));
        when(buckets.insertBucket(anyString(),any(),anyLong())).thenThrow(new DuplicateKeyException("race"));
        assertThatThrownBy(this::promote).hasMessage("study-room-participant-time-conflict");
        verify(bookings,never()).promote(anyLong());verifyNoInteractions(outbox);
    }

    @Test void missing_snapshot_cannot_silently_promote_a_room() {
        queue(List.of(booking(1,14,16)));when(members.forBookings(anyList())).thenReturn(List.of());
        assertThatThrownBy(this::promote).hasMessage("study-room-booking-members-missing");
        verify(bookings,never()).promote(anyLong());
    }

    @Test void room_replay_uses_fresh_lookup_before_interpreting_booked_team() {
        var existing=booking(1,14,16);existing.setStatus(StudyRoomBookingStatus.CONFIRMED);
        var room=new ReservableResourceEntity();room.setId(1L);room.setResourceType("STUDY_ROOM");room.setStatus(ResourceStatus.PUBLISHED);
        when(resources.lockById(1L)).thenReturn(room);
        when(bookings.findReplayFresh("student-1","request_123")).thenReturn(existing);
        var response=service.book(new CreateStudyRoomBookingRequest(1L,2L,existing.getStartsAt(),existing.getEndsAt()),
                "request_123",new CampusAuthenticatedPrincipal("student-1",Set.of("STUDENT"),"S1"));
        assertThat(response.id()).isEqualTo("1");verify(teams).lockById(2L);
        verifyNoInteractions(outbox,buckets);
    }

    @Test void lecture_replay_after_slot_wait_does_not_consume_capacity_or_insert_an_event() {
        var slots=mock(ResourceSlotMapper.class);var orders=mock(ReservationOrderMapper.class);
        var reserve=new ReservationServiceImpl(slots,resources,orders,mock(ReservationWaitlistEntryMapper.class),outbox,
                mock(PublicCatalogCacheInvalidation.class),clock,mock(ReservationAdmissionGuard.class));
        var existing=new ReservationOrderEntity();existing.setId(7L);existing.setSlotId(1L);existing.setStatus(ReservationStatus.CONFIRMED);
        when(orders.findReplayFresh("student-1","request_123")).thenReturn(existing);
        assertThat(reserve.reserve(1L,"student-1","request_123").reservationId()).isEqualTo(7L);
        var order=inOrder(slots,orders);order.verify(orders).selectOne(any());order.verify(slots).lockById(1L);
        order.verify(orders).findReplayFresh("student-1","request_123");
        verify(slots,never()).tryConsumeCapacity(anyLong());verifyNoInteractions(outbox);
    }
}
