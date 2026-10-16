package com.campusreserve.reservation.audit;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Test-only, request-scoped timing; never records JWTs, SQL parameters or business bodies. */
public final class AuditRecorder {
    public static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();
    public static final Queue<Sample> COMPLETED = new ConcurrentLinkedQueue<>();

    public static class Timing {
        public int calls;
        public double millis;
        public int rows;
        public final List<Double> executions = new ArrayList<>();
    }
    public static class Phase {
        public double millis;
        public int physicalQueries;
        public int logicalQueries;
        public int candidateRows;
    }
    public static class Sample {
        public String caseId;
        public String requestId;
        public int httpStatus;
        public String outcome;
        public long connectionId;
        public double serverMillis;
        public Double transactionToCommitMillis;
        public Double acquiredLockToCommitMillis;
        public int physicalQueries;
        public int logicalQueries;
        public int candidateRows;
        public final Map<String, Timing> sql = new LinkedHashMap<>();
        public final Map<String, Timing> stages = new LinkedHashMap<>();
        public final List<Phase> promote = new ArrayList<>();
        public transient long txStart;
        public transient long lockAcquired;
        public transient long commitCompleted;
    }

    public static long[] enter(String name) {
        Sample sample = CURRENT.get();
        if (sample == null) return null;
        long now = System.nanoTime();
        if (name.equals("doBegin")) sample.txStart = now;
        return new long[]{now, sample.physicalQueries, sample.logicalQueries, sample.candidateRows};
    }

    public static void exit(String name, long[] checkpoint, Throwable error) {
        Sample sample = CURRENT.get();
        if (sample == null || checkpoint == null) return;
        long now = System.nanoTime();
        double millis = (now - checkpoint[0]) / 1_000_000.0;
        Timing timing = sample.stages.computeIfAbsent(name, key -> new Timing());
        timing.calls++;
        timing.millis += millis;
        timing.executions.add(millis);
        if (name.equals("doCommit") && error == null) {
            sample.commitCompleted = now;
            if (sample.txStart != 0) sample.transactionToCommitMillis = (now - sample.txStart) / 1_000_000.0;
            if (sample.lockAcquired != 0) sample.acquiredLockToCommitMillis = (now - sample.lockAcquired) / 1_000_000.0;
        }
        if (name.equals("promoteWaiting")) {
            Phase phase = new Phase();
            phase.millis = millis;
            phase.physicalQueries = sample.physicalQueries - (int) checkpoint[1];
            phase.logicalQueries = sample.logicalQueries - (int) checkpoint[2];
            phase.candidateRows = sample.candidateRows - (int) checkpoint[3];
            sample.promote.add(phase);
        }
    }
}
