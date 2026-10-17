# Lecture asynchronous reservation — simplified request commands

> RETIRED 2026-10-17: the learner requested restoration to synchronous booking.
> This file is historical design documentation, NOT current endpoints or code.
> Both async implementations and their dedicated migrations have been removed.
> See [current restoration](lecture-sync-restoration-2026-10-17.md).

This described the simplified experiment. Reports dated 2026-10-16 describe the
superseded sequence/head-scanning implementation; their measurements are not
measurements of this version. Study-room code and round-one fixes are unchanged.

## API and feature switches

Opt in with `LECTURE_ASYNC_ENABLED=true`, `OUTBOX_DELIVERY_ENABLED=true`, and
`RESERVATION_ADMISSION_RATE_LIMIT_ENABLED=true`. Existing connection settings
and production admission thresholds are unchanged. The default remains disabled.

`POST /api/v1/slots/{slotId}/reservation-requests` requires a student JWT and an
8–80 character `Idempotency-Key` (letters, digits, underscore, hyphen).
The owner is taken from the authenticated principal, never from the body.
After the acceptance transaction commits, HTTP 202 and Location are returned:

```json
{"code":200,"message":"success","data":{"requestId":"<UUID>","status":"PROCESSING","reservationId":null,"failureCode":null}}
```

`GET /api/v1/reservation-requests/{requestId}` returns the owner's durable
PROCESSING / CONFIRMED / WAITLISTED / FAILED result; another owner gets 404.
Replaying POST with the same owner/key returns the same request and stored
status (which may already be final); using that key for another slot is 409.
202 means accepted, not booked. Poll with an interval/backoff. Later cancellation,
promotion and fulfilment are read through the existing reservation APIs: the
request row preserves the original execution outcome, not every later order change.

## Actual execution order

1. `LectureReservationRequestServiceImpl.submit`: fast replay lookup, feature
   checks, existing Redis Lua admission; no Redis inventory deduction.
2. `LectureRequestPersistence.accept`: validate slot/resource using ordinary
   reads, insert request and `LectureRequestOutbox.recordRequested` in one
   READ_COMMITTED transaction. No sequence allocation or resource-slot lock in
   HTTP acceptance. A duplicate-key loser re-reads after its transaction rolls back.
3. Commit before returning 202. On uncertain commit, return sanitized 500;
   the caller must retry the SAME key to find whether acceptance committed.
4. Existing Outbox scheduler leases and publishes to `campus.lecture.commands`,
   route `lecture.reserve`. Confirm ACK AND absence of a mandatory Return are
   required before marking PUBLISHED. Its leasing/retry logic is unchanged.
5. The JSON command is `{schemaVersion:2,eventId:<UUID>,requestId:<UUID>}`.
   `eventId` is exactly the Outbox eventId, not regenerated per delivery.
   The message does not supply a user, JWT or mutable booking parameters.
6. `LectureReservationListener` deserializes and validates; the worker processes
   that specific request, not a queue head. It temporarily installs the internal
   `LECTURE_QUEUE_WORKER` authority and restores the old security context in finally.
7. A worker transaction locks original resource_slot first, then the request row.
   It inserts the eventId into `lecture_reservation_inbox`. A duplicate insert
   ends normally without another booking. A different eventId for a finished
   request also does not rebook, because only PROCESSING requests execute.
8. `ReservationService.reserveAccepted` joins that transaction and reuses the
   original reservation body: idempotency, bookability, conditional capacity
   UPDATE, confirmed order or waitlist, notification Outbox, cache invalidation.
   Inbox, order/capacity/waitlist, notification Outbox and final request result
   commit together. The listener returns only after commit, allowing AUTO ACK.

The request table serves HTTP idempotency, reliable acceptance and polling.
The Inbox serves eventId deduplication. They are not interchangeable. There is
no additional per-slot sequence table, head scan, drain loop or recovery scanner.

## Ordering and failure boundaries

New admission does **not promise strict request-arrival FIFO**. Two consumers,
redeliveries and retries may reorder which request obtains the slot lock first.
The existing cancellation algorithm still promotes the oldest eligible waitlist
entry; no SKIP LOCKED was introduced. Original capacity and active-order unique
constraints remain authoritative. Async admission moves the original slot-lock
wait off the HTTP request, not out of the system or out of the consumer transaction.

- Acceptance/Outbox insert fails: rollback, no successful acceptance response.
- Publisher fails/Return/timeout: original Outbox stays recoverable for retry.
- Crash after publish before PUBLISHED: duplicate command is safe through Inbox.
- Business rejection: first booking transaction rolls back, then a fresh
  slot-locked transaction commits Inbox + FAILED + sanitized business code.
- Infrastructure or uncertain commit failure: propagate, roll back where
  possible, retry up to **3 total attempts** (1s/2s backoff), then reject to
  `<command-queue>.dlq`. Do not falsely finalize FAILED on an uncertain commit.
- Malformed/version-1/unknown-request commands: non-requeue rejection to DLQ;
  the retry policy excludes this exception, so no poison retry loop.
- Exhausted failures can leave a request PROCESSING. Diagnose/fix and explicitly
  replay the original command/eventId. There is no automatic DLQ redrive or
  periodic recovery scanner. Notification's DLQ command expects notification
  events and is NOT a compatible lecture-command replay tool.

Restrict exchange publishing permissions. Inbox assumes trusted, unique eventIds
from this Outbox; it is not a substitute for broker authorization. Broker/DB
durability does not imply a bounded time to final result or production SLA.

## Upgrade and rollback

V12 remains immutable. V13 creates the consumer Inbox and removes the obsolete
sequence table, queue indexes and backoff fields; request/order history is retained.

Before deploying V13: stop async admission on all entry points, leave the OLD
consumers/Outbox running, and drain all PROCESSING requests, unpublished lecture
commands and old broker messages (including unacked and DLQ). Inspect failures;
do not delete requests to bypass the guard. Check at least:

```sql
SELECT COUNT(*) FROM lecture_reservation_request WHERE status='PROCESSING';
SELECT COUNT(*) FROM reservation_outbox_event
WHERE event_type='LECTURE_RESERVATION_REQUESTED' AND publish_status<>'PUBLISHED';
```

V13 fails before business DDL if either count is nonzero. Also check RabbitMQ:
the SQL guard cannot see broker backlog. It requires CREATE TEMPORARY TABLES.
MySQL DDL is not fully transactional: take the normal schema backup and follow
Flyway repair procedures if an upgrade fails; do not edit already applied V12.
Do not mix V1 and V2 workers. V1 commands are intentionally not interpreted as V2.

For runtime rollback, stop async admission, keep the V2 consumers/Outbox alive
until requests and queues are drained, then disable async on all instances and
restore the original synchronous endpoint. Keep V13 tables and request history;
the old binary expecting V12 columns cannot be deployed against V13 directly.

## Validation

`mvn test` runs ordinary regression. Explicit real-infrastructure validation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/benchmark/lecture-async-live.ps1
```

The runner creates and cleans only its own unique MySQL schema and RabbitMQ
topology. The 500-caller test raises admission quotas ONLY for the test and
measures acceptance separately from final processing. It checks request-specific
deduplication, rollback, bounded retries/DLQ, original cancellation promotion,
Broker Returns, expired-lease duplicate delivery and 500 final outcomes. It does
not measure Gateway or Notification Consumer throughput, prove multi-instance
performance, or verify real process-kill/host-failure recovery.

The browser demo remains on the synchronous endpoint by default; enabling this
opt-in API does not silently change the existing browser response contract.
