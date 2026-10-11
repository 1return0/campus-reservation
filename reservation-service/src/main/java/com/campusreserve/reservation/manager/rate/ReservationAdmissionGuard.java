package com.campusreserve.reservation.manager.rate;

/**
 * Protects the synchronous reservation write path from a burst of new requests.
 * It is an admission control only: MySQL remains the capacity source of truth.
 */
public interface ReservationAdmissionGuard {

    void requireAdmission(Long slotId, String requesterReference);
}
