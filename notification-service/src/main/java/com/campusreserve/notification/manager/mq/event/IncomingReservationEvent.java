package com.campusreserve.notification.manager.mq.event;

import java.time.Instant;

/** Consumer-owned view of the public reservation event JSON contract. */
public record IncomingReservationEvent(
        String eventId,
        int schemaVersion,
        String eventType,
        Long reservationId,
        String reservationNo,
        Long slotId,
        String requesterReference,
        String reservationStatus,
        Instant occurredAt
) {
}
