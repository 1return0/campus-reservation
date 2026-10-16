# CampusReserve service architecture

## Why these services exist

The platform is not split by CRUD screens. Each service owns a business state
that can evolve and be deployed independently:

```text
browser
  -> gateway-service
      -> reservation-service -- local MySQL transaction --> reservation outbox
      -> RabbitMQ exchange --> notification-service -- MySQL Inbox --> notification record
```

`reservation-service` owns the only authoritative quota and waitlist facts.
`notification-service` owns whether a notification was accepted and delivered.
It never reaches into reservation tables. A gateway routes requests but does
not contain reservation decisions.

## Consistency contract

1. A reservation, its waitlist transition, and its outbox row are written in
   one MySQL transaction.
2. A publisher leases unfinished outbox rows and uses RabbitMQ confirms before
   marking them published. A timeout leaves the row retryable.
3. Notification consumption first records `event_id` in its own Inbox table.
   A duplicate event ends successfully without a duplicate notification.
4. Staff fulfilment transitions use database-guarded state changes:
   `CONFIRMED -> CHECKED_IN -> COMPLETED`, or `CONFIRMED -> NO_SHOW`.
   The completing staff member must be the staff member who checked in the
   reservation. A retry by that same worker after a successful target-state
   write is returned as a no-op and does not create a duplicate Outbox event.
5. Therefore the broker path is at-least-once; business effects are idempotent.
   The design does **not** claim end-to-end exactly-once delivery.
6. An administrator's maintenance cancellation locks the slot before changing
   it to `CANCELLED`, then changes every active confirmed/waitlisted order to
   `CANCELLED_BY_ADMIN`, cancels waiting entries, writes one Outbox row per
   affected student and stores an auditable cancellation record in the same
   transaction. Booking and student cancellation use the same slot lock so
   they cannot commit a new active order unnoticed during that operation.

## Time-window contract

`resource_slot` is not merely a capacity row. It has an operational life cycle:

1. Students can reserve only an `OPEN` slot whose start time is still future.
2. Students can cancel only before that start time, so a cancellation cannot
   promote a waiter into an already-running activity.
3. Staff can check in only during `[starts_at, ends_at)`; no-show becomes
   available only after the end time.
4. A scheduler closes elapsed `OPEN` slots with one guarded SQL update. It is
   safe for multiple Reservation instances because the predicate itself is
   idempotent. Closing slots invalidates only the public open-slot cache.

## Concurrency contract

The normal MyBatis-Plus Mapper covers single-row CRUD and ordinary query
filters. The following operations use narrow handwritten SQL because their
correctness depends on the `WHERE` condition being evaluated by MySQL:

- decrement capacity only when `available_capacity > 0`;
- release capacity without exceeding `capacity`;
- lock the oldest waiting candidate by `created_at ASC, id ASC` using ordinary
  `FOR UPDATE`; wait for a locked queue head rather than skipping ahead;
- lock the resource row, then reject any overlapping open slot interval before
  creating a new slot;
- update a reservation fulfilment state only when its prior state and staff
  ownership still match.

Redis will later reduce database contention for popular slots, but MySQL
remains the source of truth and the final oversell guard.

## Public catalog cache

When `RESERVATION_REDIS_ENABLED=true`, `reservation-service` caches only the
published resource list and each resource's open-slot list. Reservation writes
never rely on Redis: capacity and idempotency are still decided by MySQL. A
catalog change or a successful reservation transaction registers an
after-commit invalidation, so a rolled-back transaction cannot evict data as if
it had become visible. Redis is disabled by default for isolated local tests.

## Request correlation

`gateway-service` establishes an `X-Request-Id` at ingress and propagates it
to downstream HTTP calls. Servlet services return the same header and attach
it to their logging MDC. It is an observability correlation key only: roles and
user identity continue to come solely from verified JWTs.

## Operations surface

- All services expose liveness and readiness health probes below
  `/actuator/health/**`. Health probes are deliberately unauthenticated for
  container orchestration; non-health management endpoints remain outside the
  public API contract.
- `GET /api/v1/admin/operations/outbox-summary` is ADMIN-only. It exposes only
  pending/leased counts and the oldest pending timestamp, never event payloads
  or student information. This supports backlog alerts without granting direct
  table access.

## Local runtime topology

| Component | Local port | Notes |
| --- | --- | --- |
| gateway-service | 8200 | single browser entry |
| identity-service | 8201 | account, role and JWT issuer |
| reservation-service | 8202 | existing root Spring Boot module |
| notification-service | 8203 | event consumer |
| MySQL | 3306 | separate `campus_reserve` and `campus_notification` schemas |
| RabbitMQ | 5672 | asynchronous event transport |
| Nacos 3 | 8848 | optional service registration/discovery |

No external component is needed for the current reservation-service unit
tests. A full smoke test explicitly starts MySQL and RabbitMQ. It can run with
Nacos discovery disabled and static Gateway upstream URIs; Nacos discovery is
kept as a deployment option and is not claimed as part of that smoke result.
