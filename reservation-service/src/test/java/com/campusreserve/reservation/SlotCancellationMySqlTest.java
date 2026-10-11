package com.campusreserve.reservation;

import com.campusreserve.CampusReserveApplication;
import com.campusreserve.reservation.dto.req.CancelSlotRequest;
import com.campusreserve.reservation.dto.resp.SlotCancellationResponse;
import com.campusreserve.reservation.service.SlotCancellationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises Flyway V6 and the whole local transaction against real MySQL. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CampusReserveApplication.class, properties = {
        "NACOS_DISCOVERY_ENABLED=false",
        "OUTBOX_DELIVERY_ENABLED=false",
        "SLOT_LIFECYCLE_ENABLED=false",
        "RESERVATION_REDIS_ENABLED=false",
        "RESERVATION_ADMISSION_RATE_LIMIT_ENABLED=false",
        "campus-reserve.security.jwt.hmac-base64-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class SlotCancellationMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("campus_reserve_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SlotCancellationService cancellationService;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void migration_and_cancellation_keep_orders_waitlist_audit_and_outbox_consistent() {
        LocalDateTime future = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(3);
        jdbc.update("""
                INSERT INTO reservable_resource
                    (id, resource_code, resource_name, resource_type, status, no_show_grace_minutes, version)
                VALUES (10, 'ROOM_A10', 'Room A10', 'ROOM', 'PUBLISHED', 15, 0)
                """);
        jdbc.update("""
                INSERT INTO resource_slot
                    (id, resource_id, starts_at, ends_at, capacity, available_capacity, status, version)
                VALUES (20, 10, ?, ?, 1, 0, 'OPEN', 0)
                """, future, future.plusHours(1));
        jdbc.update("""
                INSERT INTO reservation_order
                    (id, reservation_no, slot_id, requester_reference, idempotency_key, status, active_marker, version)
                VALUES (30, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 20, 'student-1', 'reserve-001', 'CONFIRMED', 1, 0),
                       (31, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 20, 'student-2', 'reserve-002', 'WAITLISTED', 1, 0)
                """);
        jdbc.update("""
                INSERT INTO reservation_waitlist_entry
                    (id, reservation_id, slot_id, requester_reference, status)
                VALUES (40, 31, 20, 'student-2', 'WAITING')
                """);

        SlotCancellationResponse result = cancellationService.cancelForMaintenance(
                20L, "admin-1", new CancelSlotRequest("ventilation repair")
        );

        assertThat(result.confirmedCount()).isEqualTo(1);
        assertThat(result.waitlistedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM resource_slot WHERE id = 20", String.class))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("SELECT status FROM reservation_order WHERE slot_id = 20", String.class))
                .containsExactly("CANCELLED_BY_ADMIN", "CANCELLED_BY_ADMIN");
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_waitlist_entry WHERE id = 40", String.class))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_outbox_event WHERE event_type = 'RESERVATION_CANCELLED_BY_ADMIN'", Integer.class))
                .isEqualTo(2);
        assertThat(cancellationService.cancelForMaintenance(
                20L, "admin-1", new CancelSlotRequest("ventilation repair")
        ).confirmedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_outbox_event", Integer.class))
                .isEqualTo(2);
    }
}
