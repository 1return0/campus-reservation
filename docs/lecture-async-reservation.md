# Lecture asynchronous reservation (opt-in MVP)

## Scope and compatibility

Only lecture fixed-slot booking is asynchronous. Study-room booking/promotion is unchanged. `LECTURE_ASYNC_ENABLED=false` remains the default; no development/production credentials or live service configuration have been changed.

Enable **all** of the following on upgraded Reservation instances:

```properties
LECTURE_ASYNC_ENABLED=true
OUTBOX_DELIVERY_ENABLED=true
RESERVATION_ADMISSION_RATE_LIMIT_ENABLED=true
```

Use the existing RabbitMQ/Redis/MySQL connection settings. Default admission quotas are unchanged. Async submission refuses with 503 if required switches are missing; do not replace the real admission guard with a no-op in production. The Redis guard remains fail-closed when enabled.

## API

```http
POST /api/v1/slots/{slotId}/reservation-requests
Authorization: Bearer <student JWT>
Idempotency-Key: <8-80 letters/digits/_/->
```

No browser-supplied owner ID. HTTP **202**, after the persistence transaction commits:

```json
{"code":200,"message":"success","data":{"requestId":"<UUID>","status":"PROCESSING","reservationId":null,"failureCode":null}}
```

`Location: /api/v1/reservation-requests/{requestId}`. POST replays return the same request ID and its stored status (possibly already final). Reusing a key for another slot returns 409. An accepted request is not yet a confirmed reservation.

```http
GET /api/v1/reservation-requests/{requestId}
Authorization: Bearer <same student JWT>
```

Returns PROCESSING, CONFIRMED, WAITLISTED or FAILED; another student's request returns 404. `reservationId` links to the existing reservation APIs. The final request outcome is the original booking result; subsequent cancellation/promotion/fulfilment is queried through the reservation API. Clients should poll with a modest interval/backoff, not tight loops.

The original `POST /api/v1/slots/{slotId}/reservations` is retained. With async mode enabled, new lecture reservations on that endpoint return `409 lecture-async-reservation-required` so they cannot jump the accepted queue. Existing synchronous idempotent replays still work, and non-lecture fixed slots keep their original synchronous path.

## Ordering and transactions

1. Fast idempotency read, existing Redis Lua admission.
2. Short MySQL transaction locks **lecture_request_sequence**, allocates per-slot sequence, inserts **lecture_reservation_request** and a command into the **existing reservation_outbox_event**. This is NOT the resource-slot lock. Allocation commits before a later allocation can pass it; rolled-back allocations do not create invisible earlier queue entries.
3. Only after commit does the HTTP service return 202. An insert/commit error is not accepted.
4. Existing Outbox lease → publish to a dedicated `campus.lecture.commands` exchange (`lecture.reserve`) → Publisher Confirm **and** mandatory Return check → mark published. Notification exchange/routing is unchanged.
5. Two consumers may receive wakeups in any order. A wakeup only identifies the slot, not which request wins. Each attempt first locks the **original resource_slot**, then selects the earliest committed PROCESSING request by sequence **without SKIP LOCKED**. A deferred head blocks later requests.
6. Worker invokes `ReservationService.reserveAccepted()` with internal `LECTURE_QUEUE_WORKER` authority. It executes the same private reservation body as the synchronous method, skipping only a second Redis admission. Browser student authority cannot call this worker-only method through method security.
7. Original conditional capacity update, order/waitlist creation, notification Outbox and final request result commit in **one transaction**. Existing slot/maintenance exclusion, unique constraints and cancellation/promotion remain in use.

FIFO means committed admission order for the same slot, not who first clicked or opened a TCP connection. Existing waitlist selection still uses its original `created_at,id` ordering. There is no Rabbit single-consumer/FIFO assumption and no Redis inventory pre-deduction.

## Failure handling

- Producer fails before commit: no accepted request without its event; HTTP is not 202. A transaction-level uncertain commit returns sanitized `500 lecture-request-commit-uncertain`; retry the same idempotency key because a lost connection does not prove commit failed.
- Publish fails, times out or is returned: existing Outbox retry/lease recovery; request remains PROCESSING.
- Crash after confirm before published marking: the same event can be republished; repeated wakeups do not create additional orders/notification events.
- Worker DB failure/crash: the complete booking attempt rolls back. Deferred head remains PROCESSING; bounded exponential delay (up to 60 seconds), measured using **DB time**, prevents immediate database retries. If the database is unavailable even for defer, the durable request remains untouched.
- Permanent business failure (closed slot, duplicate active reservation, etc.): rollback first, then persist FAILED in a fresh slot-locked transaction. No partial booking changes survive.
- Recovery scheduler (5 seconds) atomically schedules new Outbox wakeups for due unfinished slots, coalesced to at most once per 30 seconds per batch. It does not bypass the database head or execute booking on a scheduler thread.
- Malformed/unsupported MQ messages: reject once to dedicated `<command-queue>.dlq`; no automatic poison-message requeue loop. Durable requests can still recover independently. Restrict broker publish permissions; the command carries slot/version only, not JWT/user data.

Infrastructure failures can leave PROCESSING until recovery; this MVP has no user-facing request-cancellation/deadline or automatic administrative repair for persistent corrupt database data. Monitor PROCESSING age, attempts and command DLQ; do not silently delete queue heads.

## Safe rollback

Stop new async submission traffic; **keep Outbox, consumers and recovery running**. Check:

```sql
SELECT status,COUNT(*) FROM lecture_reservation_request GROUP BY status;
SELECT MIN(created_at),MAX(attempts) FROM lecture_reservation_request WHERE status='PROCESSING';
```

Wait for PROCESSING=0 (investigate failures/backlog instead of skipping heads), then set `LECTURE_ASYNC_ENABLED=false` on **all** instances and restore traffic to the original synchronous endpoint. Keep V12 tables and existing Outbox data; no destructive schema downgrade/reset. Do not run old synchronous nodes and new asynchronous admission nodes as an uncontrolled mixed deployment.

## Verification and limits

Run ordinary regression with `mvn test`. Opt-in live validation (from repository root):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/benchmark/lecture-async-live.ps1
```

It reads ignored local connection settings; creates a unique `campus_audit_<run>` schema and unique MQ exchange/queues, and deletes only those owned resources in cleanup. Redis uses the new test slot keys with ordinary limiter TTL. Admission quotas are increased **only in this test** so a low threshold cannot mask database contention. The 500-simultaneous-caller case explicitly uses test Tomcat accept-count=1000 and a 30-second client connection timeout; these are not changes to deployment defaults.

This decouples HTTP from the long original reservation lock, not from MySQL or all queueing. The short sequencing row, connection pool, Outbox batch delivery and single-slot worker still bound throughput. 202 latency and time to final result are different metrics. No production SLA, multi-instance deployment throughput, real process-kill/host outage, Gateway throughput or Notification Consumer end-to-end performance claim is made by this isolated test.

## Modification map

- Schema: `reservation-service/src/main/resources/db/migration/V12__lecture_async_requests.sql`.
- Acceptance/query: `LectureReservationRequestController`, `LectureReservationRequestService[Impl]`, `LectureRequestPersistence`, `LectureReservationRequestMapper/Entity`, `LectureRequestResponse`.
- Durable command: `LectureRequestOutbox`, `LectureReservationWakeup`, `LectureAsyncMessagingConfiguration`.
- Processing/recovery: `LectureReservationListener`, `LectureReservationWorker`, `LectureRequestRecoveryScheduler`.
- Reused integration: `ReservationService[Impl]`, `ReservationEventType`, `RabbitReservationOutboxPublisher`; no changes to existing Outbox leasing/Inbox transactions.
- Routing/config: Gateway route, Reservation `application.yml`, `deploy/.env.example`, local Compose feature flags.
- Tests: `LectureRequestContractTest`, `LectureReservationListenerTest`, `LecturePublisherTest`, `LectureReservationRequestServiceImplTest`, opt-in `LectureAsyncLiveTest`; runner `deploy/benchmark/lecture-async-live.ps1`.

The browser demo remains on the synchronous API by default. Async operation is exposed as opt-in APIs rather than silently changing the existing UI response contract.
