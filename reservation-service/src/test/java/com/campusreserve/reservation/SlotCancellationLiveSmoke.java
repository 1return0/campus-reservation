package com.campusreserve.reservation;

import com.campusreserve.CampusReserveApplication;
import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dto.req.CancelSlotRequest;
import com.campusreserve.reservation.dto.resp.SlotCancellationResponse;
import com.campusreserve.reservation.service.ReservationService;
import com.campusreserve.reservation.service.SlotCancellationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit opt-in smoke. It writes and removes only IDs generated for this run. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_LIVE_SMOKE", matches = "true")
@SpringBootTest(classes = CampusReserveApplication.class, properties = {
        "NACOS_DISCOVERY_ENABLED=false",
        "OUTBOX_DELIVERY_ENABLED=false",
        "SLOT_LIFECYCLE_ENABLED=false",
        "RESERVATION_REDIS_ENABLED=false",
        "RESERVATION_ADMISSION_RATE_LIMIT_ENABLED=false"
})
class SlotCancellationLiveSmoke {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SlotCancellationService cancellationService;
    @Autowired
    private ReservationService reservationService;

    private final long resourceId = newId();
    private final long slotId = newId();
    private final long confirmedId = newId();
    private final long waitlistedId = newId();
    private final long waitlistEntryId = newId();

    @Test
    void cancelled_future_slot_has_no_active_orders_and_exactly_one_audit_record() throws Exception {
        String run = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime future = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(3);
        jdbc.update("""
                INSERT INTO reservable_resource
                    (id, resource_code, resource_name, resource_type, status, no_show_grace_minutes, version)
                VALUES (?, ?, 'Live smoke resource', 'ROOM', 'PUBLISHED', 15, 0)
                """, resourceId, "SMOKE_" + run.substring(0, 12).toUpperCase());
        jdbc.update("""
                INSERT INTO resource_slot
                    (id, resource_id, starts_at, ends_at, capacity, available_capacity, status, version)
                VALUES (?, ?, ?, ?, 1, 0, 'OPEN', 0)
                """, slotId, resourceId, future, future.plusHours(1));
        jdbc.update("""
                INSERT INTO reservation_order
                    (id, reservation_no, slot_id, requester_reference, idempotency_key, status, active_marker, version)
                VALUES (?, ?, ?, ?, ?, 'CONFIRMED', 1, 0)
                """, confirmedId, run, slotId, "smoke-student-1", "smoke-" + run + "-1");
        jdbc.update("""
                INSERT INTO reservation_order
                    (id, reservation_no, slot_id, requester_reference, idempotency_key, status, active_marker, version)
                VALUES (?, ?, ?, ?, ?, 'WAITLISTED', 1, 0)
                """, waitlistedId, reverse(run), slotId, "smoke-student-2", "smoke-" + run + "-2");
        jdbc.update("""
                INSERT INTO reservation_waitlist_entry
                    (id, reservation_id, slot_id, requester_reference, status)
                VALUES (?, ?, ?, 'smoke-student-2', 'WAITING')
                """, waitlistEntryId, waitlistedId, slotId);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<SlotCancellationResponse> first = workers.submit(() -> {
            authenticate("ROLE_ADMIN");
            try {
                start.await();
                return cancellationService.cancelForMaintenance(
                        slotId, "smoke-admin", new CancelSlotRequest("room maintenance")
                );
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        Future<SlotCancellationResponse> second = workers.submit(() -> {
            authenticate("ROLE_ADMIN");
            try {
                start.await();
                return cancellationService.cancelForMaintenance(
                        slotId, "smoke-admin", new CancelSlotRequest("room maintenance")
                );
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        start.countDown();
        SlotCancellationResponse result;
        try {
            result = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS).confirmedCount()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
        assertThat(result.confirmedCount()).isEqualTo(1);
        assertThat(result.waitlistedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM resource_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("SELECT status FROM reservation_order WHERE slot_id = ?", String.class, slotId))
                .containsExactly("CANCELLED_BY_ADMIN", "CANCELLED_BY_ADMIN");
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_waitlist_entry WHERE id = ?", String.class, waitlistEntryId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_outbox_event WHERE aggregate_id IN (?, ?)",
                Integer.class, confirmedId, waitlistedId)).isEqualTo(2);
        authenticate("ROLE_ADMIN");
        assertThat(cancellationService.cancelForMaintenance(
                slotId, "smoke-admin", new CancelSlotRequest("room maintenance")
        ).confirmedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_outbox_event WHERE aggregate_id IN (?, ?)",
                Integer.class, confirmedId, waitlistedId)).isEqualTo(2);

        authenticate("ROLE_STUDENT");
        assertThatThrownBy(() -> cancellationService.cancelForMaintenance(
                slotId, "smoke-student-3", new CancelSlotRequest("not allowed")
        )).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reservationService.reserve(slotId, "smoke-student-3", "smoke-" + run + "-3"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("slot-not-open");
    }

    @Test
    void booking_racing_with_admin_cancellation_never_leaves_an_active_order() throws Exception {
        String run = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime future = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(3);
        jdbc.update("""
                INSERT INTO reservable_resource
                    (id, resource_code, resource_name, resource_type, status, no_show_grace_minutes, version)
                VALUES (?, ?, 'Race smoke resource', 'ROOM', 'PUBLISHED', 15, 0)
                """, resourceId, "SMOKE_" + run.substring(0, 12).toUpperCase());
        jdbc.update("""
                INSERT INTO resource_slot
                    (id, resource_id, starts_at, ends_at, capacity, available_capacity, status, version)
                VALUES (?, ?, ?, ?, 1, 1, 'OPEN', 0)
                """, slotId, resourceId, future, future.plusHours(1));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> booking = workers.submit(() -> {
            authenticate("ROLE_STUDENT");
            try {
                start.await();
                return reservationService.reserve(slotId, "smoke-student-race", "smoke-" + run);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        Future<SlotCancellationResponse> cancellation = workers.submit(() -> {
            authenticate("ROLE_ADMIN");
            try {
                start.await();
                return cancellationService.cancelForMaintenance(
                        slotId, "smoke-admin", new CancelSlotRequest("room maintenance")
                );
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        start.countDown();
        try {
            try {
                booking.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(BusinessException.class)
                        .hasMessage("slot-not-open");
            }
            cancellation.get(15, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT status FROM resource_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation_order
                WHERE slot_id = ? AND status IN ('PENDING', 'CONFIRMED', 'WAITLISTED')
                """, Integer.class, slotId)).isZero();
        int totalOrders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_order WHERE slot_id = ?", Integer.class, slotId
        );
        int adminCancellationEvents = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation_outbox_event
                WHERE event_type = 'RESERVATION_CANCELLED_BY_ADMIN'
                  AND aggregate_id IN (SELECT id FROM reservation_order WHERE slot_id = ?)
                """, Integer.class, slotId);
        assertThat(adminCancellationEvents).isEqualTo(totalOrders);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_cancellation WHERE slot_id = ?", Integer.class, slotId))
                .isEqualTo(1);
    }

    @Test
    void student_cancellation_racing_with_admin_cancellation_has_one_terminal_effect() throws Exception {
        String run = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime future = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(3);
        jdbc.update("""
                INSERT INTO reservable_resource
                    (id, resource_code, resource_name, resource_type, status, no_show_grace_minutes, version)
                VALUES (?, ?, 'Cancel race resource', 'ROOM', 'PUBLISHED', 15, 0)
                """, resourceId, "SMOKE_" + run.substring(0, 12).toUpperCase());
        jdbc.update("""
                INSERT INTO resource_slot
                    (id, resource_id, starts_at, ends_at, capacity, available_capacity, status, version)
                VALUES (?, ?, ?, ?, 1, 0, 'OPEN', 0)
                """, slotId, resourceId, future, future.plusHours(1));
        jdbc.update("""
                INSERT INTO reservation_order
                    (id, reservation_no, slot_id, requester_reference, idempotency_key, status, active_marker, version)
                VALUES (?, ?, ?, 'smoke-student-1', ?, 'CONFIRMED', 1, 0)
                """, confirmedId, run, slotId, "smoke-" + run);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> student = workers.submit(() -> {
            authenticate("ROLE_STUDENT");
            try {
                start.await();
                return reservationService.cancel(confirmedId, "smoke-student-1");
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        Future<SlotCancellationResponse> admin = workers.submit(() -> {
            authenticate("ROLE_ADMIN");
            try {
                start.await();
                return cancellationService.cancelForMaintenance(
                        slotId, "smoke-admin", new CancelSlotRequest("room maintenance")
                );
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        start.countDown();
        try {
            try {
                student.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(BusinessException.class)
                        .hasMessage("reservation-cancellation-window-closed");
            }
            assertThat(admin.get(15, TimeUnit.SECONDS).confirmedCount()).isBetween(0, 1);
        } finally {
            workers.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM resource_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_order WHERE id = ?", String.class, confirmedId))
                .isIn("CANCELLED", "CANCELLED_BY_ADMIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_outbox_event WHERE aggregate_id = ?",
                Integer.class, confirmedId)).isEqualTo(1);
    }

    @AfterEach
    void cleanOnlyThisRun() {
        SecurityContextHolder.clearContext();
        jdbc.update("""
                DELETE FROM reservation_outbox_event
                WHERE aggregate_id IN (SELECT id FROM reservation_order WHERE slot_id = ?)
                """, slotId);
        jdbc.update("DELETE FROM slot_cancellation WHERE slot_id = ?", slotId);
        jdbc.update("DELETE FROM reservation_waitlist_entry WHERE slot_id = ?", slotId);
        jdbc.update("DELETE FROM reservation_order WHERE slot_id = ?", slotId);
        jdbc.update("DELETE FROM resource_slot WHERE id = ?", slotId);
        jdbc.update("DELETE FROM reservable_resource WHERE id = ?", resourceId);
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("smoke-user", null,
                        List.of(new SimpleGrantedAuthority(role)))
        );
    }

    private static long newId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000_000_000L, 9_000_000_000_000_000L);
    }

    private static String reverse(String text) {
        return new StringBuilder(text).reverse().toString();
    }
}
