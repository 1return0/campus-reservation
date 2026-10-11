package com.campusreserve.reservation;

import com.campusreserve.CampusReserveApplication;
import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.domain.StudyRoomBookingStatus;
import com.campusreserve.reservation.dto.req.CreateStudyRoomBookingRequest;
import com.campusreserve.reservation.dto.req.CreateStudyRoomWindowRequest;
import com.campusreserve.reservation.dto.req.CreateStudyTeamRequest;
import com.campusreserve.reservation.dto.req.ReplaceStudyRoomWindowRequest;
import com.campusreserve.reservation.dto.resp.StudyRoomBookingResponse;
import com.campusreserve.reservation.service.StudyRoomService;
import com.campusreserve.reservation.service.StudyTeamService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in, real MySQL/Flyway/Mapper transaction smoke. Does not start RabbitMQ delivery. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_LIVE_SMOKE", matches = "true")
@SpringBootTest(classes = CampusReserveApplication.class, properties = {
        "NACOS_DISCOVERY_ENABLED=false", "OUTBOX_DELIVERY_ENABLED=false",
        "SLOT_LIFECYCLE_ENABLED=false", "RESERVATION_REDIS_ENABLED=false",
        "RESERVATION_ADMISSION_RATE_LIMIT_ENABLED=false"
})
class StudyRoomLiveSmoke {
    @Autowired JdbcTemplate jdbc;
    @Autowired StudyTeamService teamService;
    @Autowired StudyRoomService roomService;
    @Autowired Clock reservationClock;

    private final long roomId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
    private final long otherRoomId = ThreadLocalRandom.current().nextLong(9_000_000_001L, 18_000_000_000L);
    private final String run = UUID.randomUUID().toString().replace("-", "");

    @Test
    void real_interval_booking_waitlist_promotion_and_maintenance_are_transactional() {
        jdbc.update("""
                INSERT INTO reservable_resource
                    (id, resource_code, resource_name, resource_type, status, no_show_grace_minutes,
                     min_participants, max_participants, version)
                VALUES (?, ?, 'Live smoke study room', 'STUDY_ROOM', 'PUBLISHED', 15, 2, 4, 0)
                """, roomId, "SR_" + run.substring(0, 20).toUpperCase());
        LocalDate date = LocalDate.now(reservationClock).plusDays(1);
        LocalDateTime start = date.atTime(10, 0);
        LocalDateTime end = date.atTime(11, 0);

        authenticate("ROLE_SUPPORT_STAFF");
        String windowId = roomService.openWindow(new CreateStudyRoomWindowRequest(
                roomId, date.atTime(7, 30), date.atTime(21, 30))).id();

        CampusAuthenticatedPrincipal first = student("student-" + run + "-1", "S" + run.substring(0, 10) + "1");
        CampusAuthenticatedPrincipal second = student("student-" + run + "-2", "S" + run.substring(0, 10) + "2");
        CampusAuthenticatedPrincipal directInvitee = student("student-" + run + "-3", "S" + run.substring(0, 10) + "3");
        CampusAuthenticatedPrincipal codeJoiner = student("student-" + run + "-4", "S" + run.substring(0, 10) + "4");
        authenticate("ROLE_STUDENT");
        Long firstTeam = teamService.create(new CreateStudyTeamRequest(
                List.of(directInvitee.studentNumber())), first).teamId();
        assertThatThrownBy(() -> roomService.book(
                new CreateStudyRoomBookingRequest(roomId, firstTeam, start, end), "key" + run + "0", first))
                .hasMessage("study-team-size-or-consent-invalid");
        teamService.accept(firstTeam, directInvitee);
        var codeTeam = teamService.create(new CreateStudyTeamRequest(List.of()), second);
        Long secondTeam = codeTeam.teamId();
        teamService.join(codeTeam.inviteCode(), codeJoiner);
        StudyRoomBookingResponse confirmed = roomService.book(
                new CreateStudyRoomBookingRequest(roomId, firstTeam, start, end), "key" + run + "1", first);
        assertThat(confirmed.status()).isEqualTo(StudyRoomBookingStatus.CONFIRMED);
        var availability = roomService.availability(roomId, date);
        assertThat(availability.windows()).hasSize(1);
        assertThat(availability.occupiedIntervals()).hasSize(1);
        assertThat(availability.occupiedIntervals().get(0).startsAt()).isEqualTo(start);
        assertThat(availability.occupiedIntervals().get(0).endsAt()).isEqualTo(end);
        assertThat(roomService.book(new CreateStudyRoomBookingRequest(roomId, firstTeam, start, end),
                "key" + run + "1", first).id()).isEqualTo(confirmed.id());
        StudyRoomBookingResponse waiting = roomService.book(
                new CreateStudyRoomBookingRequest(roomId, secondTeam, start, end), "key" + run + "2", second);
        assertThat(waiting.status()).isEqualTo(StudyRoomBookingStatus.WAITLISTED);
        assertThat(roomService.cancel(Long.parseLong(confirmed.id()), first).status())
                .isEqualTo(StudyRoomBookingStatus.CANCELLED);
        assertThat(roomService.get(Long.parseLong(waiting.id()), second).status())
                .isEqualTo(StudyRoomBookingStatus.CONFIRMED);

        authenticate("ROLE_SUPPORT_STAFF");
        roomService.closeWindowForMaintenance(Long.parseLong(windowId), "maintenance smoke");
        authenticate("ROLE_STUDENT");
        assertThat(roomService.get(Long.parseLong(waiting.id()), second).status())
                .isEqualTo(StudyRoomBookingStatus.CANCELLED_BY_ADMIN);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation_outbox_event
                WHERE aggregate_type = 'study-room-booking' AND aggregate_id IN (?, ?)
                """, Integer.class, Long.parseLong(confirmed.id()), Long.parseLong(waiting.id())))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM study_room_member_bucket WHERE booking_id IN (?, ?)",
                Integer.class, Long.parseLong(confirmed.id()), Long.parseLong(waiting.id()))).isZero();
    }

    @Test
    void shortening_a_window_cancels_only_bookings_outside_the_new_interval() {
        jdbc.update("""
                INSERT INTO reservable_resource
                    (id, resource_code, resource_name, resource_type, status, no_show_grace_minutes,
                     min_participants, max_participants, version)
                VALUES (?, ?, 'Shrink smoke room', 'STUDY_ROOM', 'PUBLISHED', 15, 1, 4, 0)
                """, roomId, "SR_" + run.substring(0, 20).toUpperCase());
        LocalDate day = LocalDate.now(reservationClock).plusDays(1);
        authenticate("ROLE_SUPPORT_STAFF");
        var window = roomService.openWindow(new CreateStudyRoomWindowRequest(
                roomId, day.atTime(7, 30), day.atTime(21, 30)));
        authenticate("ROLE_STUDENT");
        var earlyStudent = student("student-" + run + "-early", "E" + run.substring(0, 10));
        var laterStudent = student("student-" + run + "-later", "L" + run.substring(0, 10));
        Long earlyTeam = teamService.create(new CreateStudyTeamRequest(List.of()), earlyStudent).teamId();
        Long laterTeam = teamService.create(new CreateStudyTeamRequest(List.of()), laterStudent).teamId();
        var early = roomService.book(new CreateStudyRoomBookingRequest(
                roomId, earlyTeam, day.atTime(8, 0), day.atTime(9, 0)), "early" + run, earlyStudent);
        var later = roomService.book(new CreateStudyRoomBookingRequest(
                roomId, laterTeam, day.atTime(10, 0), day.atTime(11, 0)), "later" + run, laterStudent);
        authenticate("ROLE_SUPPORT_STAFF");
        var changed = roomService.shortenWindow(Long.parseLong(window.id()),
                new ReplaceStudyRoomWindowRequest(day.atTime(9, 30), day.atTime(20, 0), "morning maintenance"));
        assertThat(changed.startsAt()).isEqualTo(day.atTime(9, 30));
        authenticate("ROLE_STUDENT");
        assertThat(roomService.get(Long.parseLong(early.id()), earlyStudent).status())
                .isEqualTo(StudyRoomBookingStatus.CANCELLED_BY_ADMIN);
        assertThat(roomService.get(Long.parseLong(later.id()), laterStudent).status())
                .isEqualTo(StudyRoomBookingStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation_outbox_event
                WHERE aggregate_type = 'study-room-booking' AND aggregate_id IN (?, ?)
                """, Integer.class, Long.parseLong(early.id()), Long.parseLong(later.id())))
                .isEqualTo(3);
    }

    @Test
    void concurrent_teams_cannot_both_confirm_the_same_room_interval() throws Exception {
        jdbc.update("""
                INSERT INTO reservable_resource
                    (id, resource_code, resource_name, resource_type, status, no_show_grace_minutes,
                     min_participants, max_participants, version)
                VALUES (?, ?, 'Race smoke room', 'STUDY_ROOM', 'PUBLISHED', 15, 1, 4, 0)
                """, roomId, "SR_" + run.substring(0, 20).toUpperCase());
        LocalDate day = LocalDate.now(reservationClock).plusDays(1);
        authenticate("ROLE_SUPPORT_STAFF");
        roomService.openWindow(new CreateStudyRoomWindowRequest(
                roomId, day.atTime(7, 30), day.atTime(21, 30)));
        authenticate("ROLE_STUDENT");
        var first = student("student-" + run + "-first", "F" + run.substring(0, 10));
        var second = student("student-" + run + "-second", "G" + run.substring(0, 10));
        Long firstTeam = teamService.create(new CreateStudyTeamRequest(List.of()), first).teamId();
        Long secondTeam = teamService.create(new CreateStudyTeamRequest(List.of()), second).teamId();
        var firstRequest = new CreateStudyRoomBookingRequest(
                roomId, firstTeam, day.atTime(10, 0), day.atTime(11, 0));
        var secondRequest = new CreateStudyRoomBookingRequest(
                roomId, secondTeam, day.atTime(10, 0), day.atTime(11, 0));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<StudyRoomBookingResponse> firstResult = workers.submit(() -> {
            authenticate("ROLE_STUDENT");
            try { start.await(); return roomService.book(firstRequest, "first" + run, first); }
            finally { SecurityContextHolder.clearContext(); }
        });
        Future<StudyRoomBookingResponse> secondResult = workers.submit(() -> {
            authenticate("ROLE_STUDENT");
            try { start.await(); return roomService.book(secondRequest, "second" + run, second); }
            finally { SecurityContextHolder.clearContext(); }
        });
        start.countDown();
        try {
            assertThat(List.of(firstResult.get(15, TimeUnit.SECONDS).status(),
                    secondResult.get(15, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder(StudyRoomBookingStatus.CONFIRMED,
                            StudyRoomBookingStatus.WAITLISTED);
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM study_room_booking
                WHERE resource_id = ? AND status = 'CONFIRMED'
                """, Integer.class, roomId)).isEqualTo(1);
    }

    @Test
    void one_student_cannot_confirm_overlapping_bookings_in_two_rooms() throws Exception {
        for (long id : List.of(roomId, otherRoomId)) {
            jdbc.update("""
                    INSERT INTO reservable_resource
                        (id, resource_code, resource_name, resource_type, status, no_show_grace_minutes,
                         min_participants, max_participants, version)
                    VALUES (?, ?, 'Cross-room smoke', 'STUDY_ROOM', 'PUBLISHED', 15, 1, 4, 0)
                    """, id, "SR_" + run.substring(0, 15).toUpperCase() + "_" + id);
        }
        LocalDate day = LocalDate.now(reservationClock).plusDays(1);
        authenticate("ROLE_SUPPORT_STAFF");
        roomService.openWindow(new CreateStudyRoomWindowRequest(
                roomId, day.atTime(7, 30), day.atTime(21, 30)));
        roomService.openWindow(new CreateStudyRoomWindowRequest(
                otherRoomId, day.atTime(7, 30), day.atTime(21, 30)));
        authenticate("ROLE_STUDENT");
        var shared = student("student-" + run + "-shared", "X" + run.substring(0, 10));
        var other = student("student-" + run + "-other", "Y" + run.substring(0, 10));
        Long firstTeam = teamService.create(new CreateStudyTeamRequest(List.of()), shared).teamId();
        Long secondTeam = teamService.create(new CreateStudyTeamRequest(
                List.of(shared.studentNumber())), other).teamId();
        teamService.accept(secondTeam, shared);
        var firstRequest = new CreateStudyRoomBookingRequest(
                roomId, firstTeam, day.atTime(10, 0), day.atTime(11, 0));
        var secondRequest = new CreateStudyRoomBookingRequest(
                otherRoomId, secondTeam, day.atTime(10, 0), day.atTime(11, 0));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Object> firstResult = workers.submit(() -> {
            authenticate("ROLE_STUDENT");
            try {
                start.await();
                try { return roomService.book(firstRequest, "shared" + run, shared); }
                catch (BusinessException conflict) { return conflict.getMessage(); }
            } finally { SecurityContextHolder.clearContext(); }
        });
        Future<Object> secondResult = workers.submit(() -> {
            authenticate("ROLE_STUDENT");
            try {
                start.await();
                try { return roomService.book(secondRequest, "other" + run, other); }
                catch (BusinessException conflict) { return conflict.getMessage(); }
            } finally { SecurityContextHolder.clearContext(); }
        });
        start.countDown();
        try {
            List<Object> results = List.of(firstResult.get(15, TimeUnit.SECONDS),
                    secondResult.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(value -> value instanceof StudyRoomBookingResponse).count())
                    .isEqualTo(1);
            assertThat(results).contains("study-room-participant-time-conflict");
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM study_room_booking
                WHERE resource_id IN (?, ?) AND status = 'CONFIRMED'
                """, Integer.class, roomId, otherRoomId)).isEqualTo(1);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        List<Long> bookingIds = jdbc.queryForList(
                "SELECT id FROM study_room_booking WHERE resource_id IN (?, ?)", Long.class,
                roomId, otherRoomId);
        for (Long id : bookingIds) {
            jdbc.update("DELETE FROM reservation_outbox_event WHERE aggregate_type = 'study-room-booking' AND aggregate_id = ?", id);
            jdbc.update("DELETE FROM study_room_member_bucket WHERE booking_id = ?", id);
            jdbc.update("DELETE FROM study_room_booking_member WHERE booking_id = ?", id);
            jdbc.update("DELETE FROM study_room_booking WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM study_room_open_window WHERE resource_id = ?", roomId);
        jdbc.update("DELETE FROM study_room_open_window WHERE resource_id = ?", otherRoomId);
        List<Long> teamIds = jdbc.queryForList(
                "SELECT id FROM study_team WHERE leader_reference LIKE ?", Long.class, "student-" + run + "%");
        for (Long id : teamIds) {
            jdbc.update("DELETE FROM study_team_member WHERE team_id = ?", id);
            jdbc.update("DELETE FROM study_team WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM reservable_resource WHERE id = ?", roomId);
        jdbc.update("DELETE FROM reservable_resource WHERE id = ?", otherRoomId);
    }

    private static CampusAuthenticatedPrincipal student(String user, String number) {
        return new CampusAuthenticatedPrincipal(user, Set.of("STUDENT"), number);
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "smoke", null, List.of(new SimpleGrantedAuthority(role))));
    }
}
