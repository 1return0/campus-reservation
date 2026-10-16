package com.campusreserve.reservation.audit;

import com.campusreserve.CampusReserveApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_PERFORMANCE_AUDIT", matches = "true")
@SpringBootTest(classes = {CampusReserveApplication.class, BookingPerformanceAuditLive.Probes.class},
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.address=127.0.0.1", "server.port=18602", "debug=false", "logging.level.root=WARN",
        "campus-reserve.test-attribution=true",
        "logging.level.org.apache.catalina.core=OFF",
        "logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR",
        "NACOS_DISCOVERY_ENABLED=false", "OUTBOX_DELIVERY_ENABLED=false", "SLOT_LIFECYCLE_ENABLED=false",
        "NO_SHOW_LIFECYCLE_ENABLED=false", "RESERVATION_REDIS_ENABLED=false",
        "RESERVATION_ADMISSION_RATE_LIMIT_ENABLED=false", "STUDY_ROOM_ADMISSION_ENABLED=false"
})
public class BookingPerformanceAuditLive {
    static { if ("true".equals(System.getenv("CAMPUS_RESERVE_PERFORMANCE_AUDIT"))) AuditInstrumentation.install(); }

    @DynamicPropertySource static void isolate(DynamicPropertyRegistry registry) {
        String url = System.getenv("AUDIT_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/campus_audit_[a-f0-9]{12}\\?.*")) {
            throw new IllegalArgumentException("dedicated-audit-schema-required");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @Test void runInstrumentedHttpWorkloads() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName().toString().equals("reservation-service")) root = root.getParent();
        Process process = new ProcessBuilder("node", root.resolve("deploy/benchmark/booking-audit-load.mjs").toString())
                .directory(root.toFile()).inheritIO().start();
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroy();
            throw new AssertionError("audit-load-timeout; isolated schema retained for cleanup");
        }
        assertThat(process.exitValue()).isZero();
    }

    @TestConfiguration public static class Probes {
        @Bean @org.springframework.context.annotation.Primary java.time.Clock auditClock() {
            String fixed = System.getenv("ATTRIBUTION_FIXED_INSTANT");
            return fixed == null ? java.time.Clock.system(java.time.ZoneId.of("Asia/Shanghai")) :
                    java.time.Clock.fixed(java.time.Instant.parse(fixed), java.time.ZoneId.of("Asia/Shanghai"));
        }
        @Bean AuditSqlInterceptor sqlProbe() { return new AuditSqlInterceptor(); }
        @Bean(initMethod = "start", destroyMethod = "close") AuditLockSampler lockSampler() { return new AuditLockSampler(); }
        @Bean AuditFilter auditFilter(ObjectMapper mapper) { return new AuditFilter(mapper); }
        @Bean AuditController auditController(AuditLockSampler sampler) { return new AuditController(sampler); }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class AuditFilter extends OncePerRequestFilter {
        private final ObjectMapper mapper;
        public AuditFilter(ObjectMapper mapper) { this.mapper = mapper; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                 FilterChain chain) throws ServletException, IOException {
            String label = request.getHeader("X-Audit-Case");
            if (label == null || !label.matches("audit-[a-zA-Z0-9_-]{1,100}")) { chain.doFilter(request, response); return; }
            var sample = new AuditRecorder.Sample();
            sample.caseId = label;
            AuditLockSampler.activeCase = label;
            sample.requestId = request.getHeader("X-Audit-Request");
            var wrapped = new ContentCachingResponseWrapper(response);
            long start = System.nanoTime();
            AuditRecorder.CURRENT.set(sample);
            try { chain.doFilter(request, wrapped); }
            finally {
                sample.serverMillis = (System.nanoTime() - start) / 1_000_000.0;
                sample.httpStatus = wrapped.getStatus();
                try {
                    var json = mapper.readTree(wrapped.getContentAsByteArray());
                    sample.outcome = sample.httpStatus == 200 ? json.path("data").path("status").asText("other")
                            : json.path("message").asText("unknown");
                } catch (Exception ignored) { sample.outcome = "unparsed"; }
                AuditRecorder.COMPLETED.add(sample);
                AuditRecorder.CURRENT.remove();
                wrapped.copyBodyToResponse();
            }
        }
    }

    @RestController public static class AuditController {
        private final AuditLockSampler sampler;
        public AuditController(AuditLockSampler sampler) { this.sampler = sampler; }
        public record Snapshot(List<AuditRecorder.Sample> requests, String lockSamplerStatus,
                               List<AuditLockSampler.Observation> lockObservations) { }
        @GetMapping("/__audit/drain")
        public Snapshot drain(@RequestParam String caseId) {
            List<AuditRecorder.Sample> result = new ArrayList<>();
            for (var sample : AuditRecorder.COMPLETED) {
                if (sample.caseId.equals(caseId) && AuditRecorder.COMPLETED.remove(sample)) result.add(sample);
            }
            return new Snapshot(result, sampler.status, sampler.drain(caseId));
        }
    }
}
