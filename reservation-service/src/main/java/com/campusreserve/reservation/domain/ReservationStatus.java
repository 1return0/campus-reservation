package com.campusreserve.reservation.domain;

public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    WAITLISTED,
    CANCELLED,
    CANCELLED_BY_ADMIN,
    CHECKED_IN,
    COMPLETED,
    NO_SHOW
}
