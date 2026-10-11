package com.campusreserve.reservation.dto.resp;

import java.time.Instant;

/** Non-sensitive operational view; event payloads are deliberately excluded. */
public record OutboxDeliverySummaryResponse(
        long pendingEvents,
        long activelyLeasedEvents,
        Instant oldestPendingCreatedAt
) {
}
