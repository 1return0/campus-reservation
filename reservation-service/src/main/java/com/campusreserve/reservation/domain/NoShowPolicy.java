package com.campusreserve.reservation.domain;

/**
 * Resource-level attendance defaults. A no-show is eligible only after this
 * grace period; an employee still records the fact based on real-world
 * observation rather than an unverified timer alone.
 */
public final class NoShowPolicy {

    public static final int DEFAULT_GRACE_MINUTES = 15;
    public static final int MIN_GRACE_MINUTES = 1;
    public static final int MAX_GRACE_MINUTES = 60;

    private NoShowPolicy() {
    }
}
