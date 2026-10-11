package com.campusreserve.reservation.manager.mq.event;

import com.campusreserve.reservation.domain.ReservationEventType;
import com.campusreserve.reservation.domain.ReservationStatus;

import java.time.Instant;

/**
 * Versioned message contract owned by reservation-service. Consumers depend on
 * its JSON schema, never on reservation-service entities or database tables.
 */
public record ReservationStatusChangedEvent(
        String eventId,
        int schemaVersion,
        ReservationEventType eventType,
        Long reservationId,
        String reservationNo,
        Long slotId,
        String requesterReference,
        ReservationStatus reservationStatus,
        Instant occurredAt
) {
}
