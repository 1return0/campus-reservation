package com.campusreserve.reservation.audit;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;

/** Read-only, schema-filtered Performance Schema evidence. Sampling is NOT an exact per-request lock timer. */
public class AuditLockSampler implements AutoCloseable {
    public static volatile String activeCase = "none";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "isolated-audit-lock-sampler"); thread.setDaemon(true); return thread;
    });
    private final Queue<Observation> observations = new ConcurrentLinkedQueue<>();
    private Connection connection;
    public volatile String status = "not-started";
    public record Waiting(long connectionId, long eventId, String table, String index, double currentStatementMillis) { }
    public record Observation(String caseId, long nanoTime, List<Waiting> waiting) { }
    public void start() throws Exception {
        String url = System.getenv("AUDIT_DB_URL");
        connection = DriverManager.getConnection(url, System.getenv("CAMPUS_RESERVE_DB_USERNAME"),
                System.getenv("CAMPUS_RESERVE_DB_PASSWORD"));
        String schema = URI.create(url.substring(5)).getPath().substring(1);
        worker.scheduleWithFixedDelay(() -> {
            try (var statement = connection.prepareStatement("""
                    SELECT t.PROCESSLIST_ID,w.REQUESTING_EVENT_ID,l.OBJECT_NAME,l.INDEX_NAME,
                           COALESCE(s.TIMER_WAIT/1000000000.0,0)
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l
                      ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.threads t ON t.THREAD_ID=w.REQUESTING_THREAD_ID
                    LEFT JOIN performance_schema.events_statements_current s ON s.THREAD_ID=w.REQUESTING_THREAD_ID
                    WHERE l.OBJECT_SCHEMA=? AND l.LOCK_TYPE='RECORD'
                    """)) {
                statement.setString(1, schema);
                statement.setQueryTimeout(3);
                List<Waiting> waiting = new ArrayList<>();
                try (var result = statement.executeQuery()) {
                    while (result.next()) waiting.add(new Waiting(result.getLong(1), result.getLong(2),
                            result.getString(3), result.getString(4), result.getDouble(5)));
                }
                status = "available";
                if (!waiting.isEmpty() && observations.size() < 50000) {
                    observations.add(new Observation(activeCase, System.nanoTime(), List.copyOf(waiting)));
                }
            } catch (Exception ignored) { status = "unavailable"; }
        }, 0, 20, TimeUnit.MILLISECONDS);
    }

    public List<Observation> drain(String caseId) {
        List<Observation> result = new ArrayList<>();
        for (var row : observations) if (row.caseId().equals(caseId) && observations.remove(row)) result.add(row);
        return result;
    }
    @Override public void close() throws Exception { worker.shutdownNow(); if (connection != null) connection.close(); }
}
