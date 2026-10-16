package com.campusreserve.reservation.async;

import com.campusreserve.CampusReserveApplication;
import com.campusreserve.reservation.manager.mq.delivery.*;
import com.campusreserve.reservation.service.*;
import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;
import com.campusreserve.reservation.dao.mapper.ReservationOutboxEventMapper;
import com.campusreserve.reservation.domain.ReservationEventType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusreserve.security.CampusJwtService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in only. Dedicated schema and MQ names, never production data. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_ASYNC_LIVE", matches = "true")
@SpringBootTest(classes = {CampusReserveApplication.class, LectureAsyncLiveTest.Topology.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.tomcat.accept-count=1000", "debug=false", "logging.level.root=WARN",
        "logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR",
        "NACOS_DISCOVERY_ENABLED=false", "OUTBOX_DELIVERY_ENABLED=true", "LECTURE_ASYNC_ENABLED=true",
        "SLOT_LIFECYCLE_ENABLED=false", "NO_SHOW_LIFECYCLE_ENABLED=false", "RESERVATION_REDIS_ENABLED=false",
        "RESERVATION_ADMISSION_RATE_LIMIT_ENABLED=true", "RESERVATION_ADMISSION_PER_SLOT_MAX_REQUESTS=10000",
        "RESERVATION_ADMISSION_PER_USER_PER_SLOT_MAX_REQUESTS=1000", "STUDY_ROOM_ADMISSION_ENABLED=false",
        "campus-reserve.outbox.delivery.fixed-delay-millis=600000",
        "campus-reserve.lecture-async.recovery-delay-millis=600000",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
public class LectureAsyncLiveTest {
    static final String RUN = System.getenv("ASYNC_LIVE_RUN");
    static final String EXCHANGE = "campus.async.test." + RUN;
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry registry) {
        String url = System.getenv("AUDIT_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/campus_audit_[a-f0-9]{12}\\?.*")
                || RUN == null || !RUN.matches("[a-f0-9]{12}")) throw new IllegalArgumentException("isolated-environment-required");
        registry.add("spring.datasource.url", () -> url);
        registry.add("campus-reserve.lecture-async.exchange", () -> EXCHANGE);
        registry.add("campus-reserve.lecture-async.queue", () -> EXCHANGE + ".queue");
        registry.add("campus-reserve.messaging.exchange", () -> EXCHANGE + ".events");
    }
    @TestConfiguration static class Topology {
        @Bean Declarables testNotificationSink() {
            var exchange = new TopicExchange(EXCHANGE + ".events", true, false);
            var queue = new org.springframework.amqp.core.Queue(EXCHANGE + ".notifications", true);
            return new Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange).with("reservation.#"));
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired CampusJwtService jwt;
    @Autowired LectureReservationWorker worker;
    @Autowired ReservationOutboxDeliveryScheduler delivery;
    @Autowired LectureRequestRecoveryScheduler recovery;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ReservationService reservations;
    @Autowired ReservationOutboxEventMapper outboxMapper;
    @Autowired ReservationOutboxLeaseService leases;
    @Autowired ReservationOutboxPublisher publisher;
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    final List<String> checks = new ArrayList<>();
    final Map<String, Object> measurements = new LinkedHashMap<>();
    long nextSlot = 100;

    @Test void durable_fifo_idempotent_recoverable_pipeline_and_hotspot_latency() throws Exception {
        try {
            long slot = slot(1);
            String first = submit(slot, "fifo-a", "fifo_key_a").path("requestId").asText();
            String second = submit(slot, "fifo-b", "fifo_key_b").path("requestId").asText();
            // Signals carry no priority; competing consumers must follow the persisted head.
            var pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> worker.drain(slot)));
                for (var task : tasks) task.get(30, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            assertThat(status(first)).isEqualTo("CONFIRMED");
            assertThat(status(second)).isEqualTo("WAITLISTED");
            worker.drain(slot);
            assertThat(count("reservation_order", slot)).isEqualTo(2);
            checks.add("committed FIFO, competing workers, duplicate wakeup, no oversell");
            assertThat(request("GET", "/api/v1/reservation-requests/" + first, "fifo-b", null).statusCode()).isEqualTo(404);
            assertThat(submit(slot, "fifo-a", "fifo_key_a").path("requestId").asText()).isEqualTo(first);
            checks.add("idempotent accepted replay and owner-isolated polling");

            long concurrentSlot = slot(1);
            var replayPool = Executors.newFixedThreadPool(20);
            try {
                List<Future<String>> requests = new ArrayList<>();
                for (int i = 0; i < 20; i++) requests.add(replayPool.submit(() ->
                        submit(concurrentSlot, "same-key-owner", "same_key_123").path("requestId").asText()));
                Set<String> ids = new HashSet<>();
                for (var future : requests) ids.add(future.get(30, TimeUnit.SECONDS));
                assertThat(ids).hasSize(1);
            } finally { replayPool.shutdownNow(); }
            worker.drain(concurrentSlot);
            assertThat(count("reservation_order", concurrentSlot)).isEqualTo(1);
            checks.add("20 concurrent same-key submissions all 202, one request/order");

            // A failed producer transaction cannot return accepted or leave a request without its event.
            jdbc.execute("CREATE TRIGGER async_fail_accept BEFORE INSERT ON reservation_outbox_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected'");
            long failedAcceptSlot = slot(1);
            try {
                assertThat(request("POST", "/api/v1/slots/" + failedAcceptSlot + "/reservation-requests", "accept-fail", "accept_failure").statusCode()).isEqualTo(500);
                assertThat(count("lecture_reservation_request", failedAcceptSlot)).isZero();
            } finally { jdbc.execute("DROP TRIGGER async_fail_accept"); }
            checks.add("acceptance Outbox failure rolls back request; HTTP 500, never 202/403");

            long rollbackSlot = slot(1);
            String retry = submit(rollbackSlot, "retry-owner", "retry_request").path("requestId").asText();
            submit(rollbackSlot, "later-owner", "later_request");
            jdbc.execute("CREATE TRIGGER async_fail_execute BEFORE INSERT ON reservation_outbox_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected'");
            try {
                assertThat(worker.processOne(rollbackSlot)).isFalse();
                assertThat(count("reservation_order", rollbackSlot)).isZero();
                assertThat(jdbc.queryForObject("SELECT available_capacity FROM resource_slot WHERE id=?", Integer.class, rollbackSlot)).isEqualTo(1);
                assertThat(status(retry)).isEqualTo("PROCESSING");
                assertThat(worker.processOne(rollbackSlot)).isFalse(); // backoff head blocks later requests
                assertThat(jdbc.queryForObject("SELECT attempts FROM lecture_reservation_request WHERE request_id=?", Integer.class, retry)).isEqualTo(1);
            } finally { jdbc.execute("DROP TRIGGER async_fail_execute"); }
            jdbc.update("UPDATE lecture_reservation_request SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE slot_id=?", rollbackSlot);
            worker.drain(rollbackSlot);
            assertThat(status(retry)).isEqualTo("CONFIRMED");
            assertThat(count("reservation_order", rollbackSlot)).isEqualTo(2);
            checks.add("worker DB failure atomically rolls back capacity/order/notification; head retry preserves FIFO");

            // Original cancellation/promote transaction and notification events are unchanged.
            long winner = jdbc.queryForObject("SELECT reservation_id FROM lecture_reservation_request WHERE request_id=?", Long.class, first);
            asStudent("fifo-a", () -> reservations.cancel(winner, "fifo-a"));
            assertThat(jdbc.queryForObject("SELECT status FROM reservation_order WHERE requester_reference='fifo-b'", String.class)).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("SELECT available_capacity FROM resource_slot WHERE id=?", Integer.class, slot)).isZero();
            checks.add("original cancellation FIFO promotion and notification Outbox preserved");

            // Maintenance before consumption is a terminal business result, not an endlessly retried poison request.
            long closed = slot(1);
            String closedRequest = submit(closed, "closed-owner", "closed_request").path("requestId").asText();
            jdbc.update("UPDATE resource_slot SET status='CANCELLED' WHERE id=?", closed);
            worker.drain(closed);
            assertThat(status(closedRequest)).isEqualTo("FAILED");
            assertThat(count("reservation_order", closed)).isZero();
            checks.add("maintenance rejection persists FAILED without reservation side effects");

            // Holding the original slot lock cannot block HTTP acceptance (proves separation, not just timing).
            long blocked = slot(1);
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var holder = Executors.newSingleThreadExecutor();
            Future<?> held = holder.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForList("SELECT * FROM resource_slot WHERE id=? FOR UPDATE", blocked);
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("lock-test-timeout"); }
                catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            try { submit(blocked, "lock-independent", "lock_independent"); }
            finally { release.countDown(); held.get(15, TimeUnit.SECONDS); holder.shutdownNow(); }
            measurements.put("acceptWhileOriginalSlotLockedMillis", millis(start));
            worker.drain(blocked);
            checks.add("HTTP accepted while original resource_slot lock was still held");

            // 500 simultaneous callers; admission quota raised ONLY in this test.
            long hot = slot(20);
            var perfPool = Executors.newFixedThreadPool(500);
            var perfReady = new CountDownLatch(500);
            var perfGo = new CountDownLatch(1);
            List<Double> latencies = Collections.synchronizedList(new ArrayList<>());
            long perfStart = System.nanoTime();
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < 500; i++) {
                    int index = i;
                    tasks.add(perfPool.submit(() -> {
                        perfReady.countDown();
                        try { perfGo.await(); } catch (InterruptedException interruption) { throw new RuntimeException(interruption); }
                        long began = System.nanoTime();
                        submit(hot, "perf-" + index, "perf_request_" + index);
                        latencies.add(millis(began));
                    }));
                }
                assertThat(perfReady.await(15, TimeUnit.SECONDS)).isTrue();
                perfStart = System.nanoTime();
                perfGo.countDown();
                for (var task : tasks) task.get(120, TimeUnit.SECONDS);
            } finally { perfPool.shutdownNow(); }
            double duration = millis(perfStart);
            Collections.sort(latencies);
            measurements.put("accept500Concurrent500", Map.of("requests", latencies.size(), "tps", 500000.0 / duration,
                    "p50Millis", percentile(latencies, .5), "p95Millis", percentile(latencies, .95), "p99Millis", percentile(latencies, .99)));
            assertThat(count("lecture_reservation_request", hot)).isEqualTo(500);

            // Real broker: start consumers after acceptance to prove backlog durability and recoverability.
            listeners.start();
            start = System.nanoTime();
            for (int i = 0; i < 40; i++) delivery.deliverPendingEvents();
            await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM lecture_reservation_request WHERE slot_id=? AND status='PROCESSING'", Integer.class, hot) == 0, 120);
            measurements.put("finish500AfterConsumerStartMillis", millis(start));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_order WHERE slot_id=? AND status='CONFIRMED'", Integer.class, hot)).isEqualTo(20);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_order WHERE slot_id=? AND status='WAITLISTED'", Integer.class, hot)).isEqualTo(480);
            assertThat(jdbc.queryForObject("SELECT available_capacity FROM resource_slot WHERE id=?", Integer.class, hot)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lecture_reservation_request r JOIN reservation_order o ON r.reservation_id=o.id WHERE r.slot_id=? AND ((r.sequence_no<=20 AND o.status<>'CONFIRMED') OR (r.sequence_no>20 AND o.status<>'WAITLISTED'))", Integer.class, hot)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_outbox_event WHERE aggregate_id IN (SELECT id FROM reservation_order WHERE slot_id=?) AND event_type IN ('RESERVATION_CONFIRMED','RESERVATION_WAITLISTED')", Integer.class, hot)).isEqualTo(500);
            checks.add("real Redis + 500 accepted + real Outbox/Rabbit confirms + 2 consumers: first 20 confirmed, next 480 waitlisted, 500 notification events atomically present");

            rabbit.convertAndSend(EXCHANGE, "lecture.reserve", "not-json");
            await(() -> admin.getQueueInfo(EXCHANGE + ".queue.dlq").getMessageCount() == 1, 10);
            checks.add("real malformed MQ message enters isolated DLQ without automatic retry loop");

            // Lost/acknowledged wake-up recovery: no new HTTP request required.
            listeners.stop();
            long recoverSlot = slot(1);
            String recoverId = submit(recoverSlot, "recovery-owner", "recovery_request").path("requestId").asText();
            jdbc.update("UPDATE reservation_outbox_event SET publish_status='PUBLISHED' WHERE aggregate_id=? AND event_type='LECTURE_RESERVATION_REQUESTED'", recoverSlot);
            recovery.recover();
            listeners.start();
            for (int i = 0; i < 40; i++) delivery.deliverPendingEvents();
            await(() -> status(recoverId).equals("CONFIRMED"), 30);
            checks.add("durable PROCESSING scanner republishes a lost wakeup through Outbox");

            // Confirm ACK alone is insufficient: an unroutable publish must leave its Outbox recoverable.
            listeners.stop();
            long unroutableSlot = slot(1);
            String unroutableId = submit(unroutableSlot, "unroutable-owner", "unroutable_request").path("requestId").asText();
            var binding = new Binding(EXCHANGE + ".queue", Binding.DestinationType.QUEUE, EXCHANGE, "lecture.reserve", null);
            admin.removeBinding(binding);
            var event = command(unroutableSlot);
            String lease = leases.acquireLease(event, Duration.ofSeconds(30));
            assertThat(lease).isNotNull();
            try {
                assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class).hasMessage("rabbitmq-message-unroutable");
                leases.releaseAfterFailure(event.getId(), lease, "rabbitmq-message-unroutable");
                assertThat(outboxMapper.selectById(event.getId()).getPublishStatus().name()).isEqualTo("PENDING");
                assertThat(status(unroutableId)).isEqualTo("PROCESSING");
                assertThat(count("reservation_order", unroutableSlot)).isZero();
            } finally { admin.declareBinding(binding); }
            lease = leases.acquireLease(event, Duration.ofSeconds(30));
            publisher.publish(event);
            leases.markPublished(event.getId(), lease);
            listeners.start();
            await(() -> status(unroutableId).equals("CONFIRMED"), 30);
            checks.add("real broker Return retains pending Outbox/request; restored routing retries successfully");

            // Crash after broker confirm, before markPublished: expired lease republishes SAME eventId.
            listeners.stop();
            long crashSlot = slot(1);
            String crashId = submit(crashSlot, "crash-owner", "crash_request").path("requestId").asText();
            var crashEvent = command(crashSlot);
            leases.acquireLease(crashEvent, Duration.ofSeconds(30));
            publisher.publish(crashEvent); // intentionally no markPublished
            jdbc.update("UPDATE reservation_outbox_event SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?", crashEvent.getId());
            String replacementLease = leases.acquireLease(crashEvent, Duration.ofSeconds(30));
            assertThat(replacementLease).isNotNull();
            publisher.publish(crashEvent);
            leases.markPublished(crashEvent.getId(), replacementLease);
            listeners.start();
            await(() -> status(crashId).equals("CONFIRMED"), 30);
            assertThat(count("reservation_order", crashSlot)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_outbox_event WHERE aggregate_id IN (SELECT id FROM reservation_order WHERE slot_id=?) AND event_type='RESERVATION_CONFIRMED'", Integer.class, crashSlot)).isEqualTo(1);
            checks.add("confirmed-publish crash window and expired-lease duplicate delivery create one order/notification event");

            Path output = Path.of(System.getenv("AUDIT_ARTIFACT_DIRECTORY"), "lecture-async-results.json");
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of("checks", checks, "measurements", measurements,
                    "scope", "direct Reservation HTTP + real isolated MySQL/Redis/Rabbit; no Gateway/notification-consumer throughput claim"));
            System.out.println("ASYNC_LIVE_RESULT " + json.writeValueAsString(measurements));
        } finally { cleanupMq(); }
    }

    long slot(int capacity) {
        long id = ++nextSlot;
        jdbc.update("INSERT INTO reservable_resource(id,resource_code,resource_name,resource_type,status,no_show_grace_minutes) VALUES(?,?,?,'LECTURE','PUBLISHED',15)", id, "ASYNC-" + id, "test lecture");
        jdbc.update("INSERT INTO resource_slot(id,resource_id,starts_at,ends_at,capacity,available_capacity,status) VALUES(?,?,TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP),TIMESTAMPADD(DAY,2,CURRENT_TIMESTAMP),?,?,'OPEN')", id, id, capacity, capacity);
        return id;
    }
    JsonNode submit(long slot, String owner, String key) {
        try {
            var response = request("POST", "/api/v1/slots/" + slot + "/reservation-requests", owner, key);
            assertThat(response.statusCode()).withFailMessage("expected202 got %s: %s", response.statusCode(), response.body()).isEqualTo(202);
            return json.readTree(response.body()).path("data");
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    HttpResponse<String> request(String method, String path, String owner, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + jwt.issue(owner, Set.of("STUDENT"), Duration.ofMinutes(10)));
        if (key != null) builder.header("Idempotency-Key", key);
        return http.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    String status(String request) { return jdbc.queryForObject("SELECT status FROM lecture_reservation_request WHERE request_id=?", String.class, request); }
    ReservationOutboxEventEntity command(long slot) { return outboxMapper.selectOne(new LambdaQueryWrapper<ReservationOutboxEventEntity>()
            .eq(ReservationOutboxEventEntity::getAggregateId, slot).eq(ReservationOutboxEventEntity::getEventType, ReservationEventType.LECTURE_RESERVATION_REQUESTED)); }
    int count(String table, long slot) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE slot_id=?", Integer.class, slot); }
    static double millis(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    static double percentile(List<Double> values, double p) { return values.get((int) Math.ceil(values.size() * p) - 1); }
    static void await(BooleanSupplier condition, int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    static void asStudent(String owner, Runnable action) {
        var old = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        SecurityContextHolder.setContext(context);
        try { action.run(); } finally { SecurityContextHolder.setContext(old); }
    }
    void cleanupMq() {
        listeners.stop();
        for (String suffix : List.of(".queue", ".queue.dlq", ".notifications")) admin.deleteQueue(EXCHANGE + suffix);
        for (String suffix : List.of("", ".dlx", ".events")) admin.deleteExchange(EXCHANGE + suffix);
    }
}
