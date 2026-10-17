# CampusReserve

CampusReserve is a Java 17 LTS microservice platform for campus venues,
equipment and activity quotas. It focuses on the hard part of a familiar
student workflow: a limited quota must never be oversold; cancellation must
promote the oldest eligible waitlist entry; and a repeated request must not
create a second reservation.

The concrete V2 scenario is a library study room: staff publish each room's
opening windows and team-size limits; students form a consenting team through
student-number invitations or an invite code, then choose a 60-240 minute
interval on a 30-minute grid. Booking opens at midnight two calendar days
before use. Forming a team does not hold a room. Confirmed reservations,
waitlist promotion, attendance handling, maintenance cancellation and student
notifications complete the local business loop. Legacy fixed-slot resources
remain separate from study rooms.

## Business closure

Lecture booking uses the synchronous API: Redis admission followed by the MySQL
slot-locked reservation transaction returns CONFIRMED or WAITLISTED directly.
Only result notifications are asynchronous through the existing Outbox/RabbitMQ/
Notification Inbox chain. Study rooms remain synchronous. The experimental
asynchronous booking API has been retired; see [restoration notes](docs/lecture-sync-restoration-2026-10-17.md).

```text
Gateway validates the bearer token and routes the request
  -> reservation-service creates a confirmed reservation or waitlist entry
  -> same local transaction writes an outbox event
  -> reliable publisher sends the event to RabbitMQ
  -> notification-service records a student notification with Inbox idempotency
  -> cancellation atomically releases a quota and promotes the oldest waiter
  -> promoted student receives a new notification
  -> support staff checks in and completes a confirmed reservation, or marks a no-show
     after that resource's configured attendance grace period (15 minutes by default)
  -> an administrator can cancel a future slot for maintenance; confirmed and
     waitlisted orders become CANCELLED_BY_ADMIN in the same transaction, with
     one Outbox event per affected student and a durable cancellation record
```

## Service boundaries

| Service | Owns | Storage | Communication |
| --- | --- | --- | --- |
| `gateway-service` | API routing and first JWT verification | none | Nacos discovery to downstream services |
| `identity-service` | student registration, administrator-provisioned support staff and JWT issuance | `campus_identity` MySQL schema | synchronous HTTP through the gateway |
| `reservation-service` | resources, slots, reservations, waitlist, staff fulfilment and outbox | `campus_reserve` MySQL schema | publishes domain events to RabbitMQ |
| `notification-service` | delivery records and message-consumption inbox | `campus_notification` MySQL schema | consumes RabbitMQ events |

The boundaries are deliberately narrow: no service reads another service's
tables. Cross-service state is eventually consistent and observable through
the Outbox/Inbox event path, rather than hidden distributed transactions.

`Nacos` is enabled only when `NACOS_DISCOVERY_ENABLED=true`, so local unit
tests remain independent of external infrastructure. The current 2025.0.x
Spring Cloud Alibaba line requires Nacos Server 3.x for the default namespace.

## Technology choices

- Java 17 LTS, Spring Boot 3.5, Spring Cloud 2025.0.x and Nacos discovery
- MySQL 8, Flyway and MyBatis-Plus for ordinary CRUD; a schema per service
- Spring Cloud Gateway and RabbitMQ for service ingress and asynchronous events
- Redis for short-lived public catalog reads, with after-commit cache invalidation,
  per-key Redisson distributed cache rebuild coordination and optional TTL jitter; an explicitly
  enabled Lua admission guard can shed hot-slot bursts. A separate optional
  room/use-date and student/room/use-date Lua guard protects study-room writes;
  public room windows can use a short-TTL cache with after-commit invalidation.
  MySQL remains the final quota, interval-conflict and idempotency guard

Public queries retain `@Cacheable(sync = true)` as the Spring interception entry,
but the Redis CacheManager wraps cache loading with a shared Redisson per-key
lock and a second cache check. On contention, callers immediately try the lock
and recheck cache with bounded jittered backoff. A refill can be returned without
first acquiring the lock. No local Future map or request-coalescing layer is used.
Lock contention waits at most one second by default, then rechecks the cache;
a still-missing value returns `503 public-cache-rebuild-busy`, not a database stampede.
Redis failures fall back to the query loader; TTL bounds staleness, not strong
consistency. Two independent JVMs sharing real Redis returned 40 results with
one instrumented loader execution in the first revision. See [that verification](docs/distributed-cache-rebuild-2026-10-14.md)
and [the bounded-retry update and its current verification](docs/cache-rebuild-bounded-retry-2026-10-14.md).
- Narrow custom Mapper SQL for capacity compare-and-update, interval overlap
  and participant-conflict checks, and ordered `FOR UPDATE` lecture waitlist claiming
- JUnit and Testcontainers for real MySQL integration tests

## Repository layout

```text
CampusReserve/
├── gateway-service/             independently runnable API gateway
├── identity-service/            account registration and JWT token issuer
├── reservation-service/         resource, slot, reservation and waitlist service
├── notification-service/        independently runnable event consumer
└── docs/architecture.md         boundaries and delivery guarantees
```

The reservation domain, JWT boundaries and Outbox/Inbox code paths are in
place. A real VM MySQL/RabbitMQ smoke has covered Flyway, registration/login,
gateway routing, role enforcement, booking idempotency, waitlisting,
cancellation/promotion, broker confirm, notification Inbox deduplication and
student notification delivery. The project does not claim Redis quota control,
Nacos 3 discovery, production multi-instance performance, or load-test results
until each is verified independently. The optional Redis Lua admission guard
remains disabled by default; opt-in real Redis checks are recorded separately.

The V2 study-room flow has an opt-in real-MySQL smoke covering consent,
idempotency, exact intervals, waitlist, maintenance and selected concurrency
cases. A separate local Chrome-to-Gateway-to-MySQL-to-RabbitMQ-to-Inbox smoke
passed for team booking, maintenance/cancellation and notification delivery.
Public availability shows occupied intervals without disclosing students and
remains advisory. Self-reported student numbers are **not** authoritative
campus identity proof; production use needs campus SSO/provisioning. Neither
local smoke proves production-scale concurrency or failure recovery. See the
[V2 business contract](docs/study-room-v2-contract.md).

The V2 Redis admission and cache switches are off by default. Unit tests cover
guard wiring, 429/503 mapping, database fallback and after-commit cache
invalidation. Opt-in VM Redis smokes passed for the Lua limiter (including a
500-request synchronized burst using 500 worker threads at a 20-admission cap)
and real cache
serialization; the four real-MySQL cases and local browser-to-notification
journey also passed with V2 Lua enabled. No throughput or latency improvement
is claimed without a controlled before/after load comparison.
The latest Redis 7.0.15 run admitted exactly 20 and rate-limited 480 requests,
with 500 peak in-flight client guard calls; see the
[500-request verification record](docs/redis-admission-500-burst-2026-10-12.md).

The maintenance-cancellation extension has automated unit coverage. On the
local VM, V6 was applied to MySQL and an opt-in smoke verified its transaction,
audit row, two affected orders, Outbox rows and same-operator replay. Separate
isolated RabbitMQ smokes verified the actual Publisher's confirm/routing and
the actual `@RabbitListener` -> Inbox -> student notification path. Those
checks are segment smokes. A separate combined browser-to-notification smoke
also passed; neither establishes multi-instance performance or load-test
results. See [verification evidence](docs/maintenance-cancellation-verification.md).

## Browser demonstration

The Gateway serves a small same-origin browser UI at `http://127.0.0.1:8200/`.
It covers student registration/login, catalog and slot browsing, study-team
invitations/consent, interval booking and availability, personal reservations
and notifications, staff fulfilment, and administrator resource/window
management including maintenance cancellation. The token stays
in page memory only; refreshing the page requires login again. A headless
Chrome acceptance run has completed the student booking -> ADMIN maintenance
cancellation -> student notification journey through the Gateway page and real
VM dependencies; other role journeys are not covered by that run. This is a
same-origin demonstration client, not a separately deployed frontend system.

Maintenance cancellation is available to ADMIN through
`PATCH /api/v1/admin/slots/{slotId}/cancel` with `{"reason":"..."}`. It
works only before the slot starts, stores the operator and reason in
`slot_cancellation`, and is an idempotent replay only for the same operator
and reason. `GET /api/v1/admin/slots/{slotId}/cancellation` exposes the record
to staff/admin. Students see the `CANCELLED_BY_ADMIN` reservation state and
receive an asynchronous notification once the event is consumed. A closed
resource cannot be reopened; ADMIN may close a resource only after all its
slots have left `OPEN`.

## Local infrastructure

`deploy/docker-compose.infrastructure.yml` starts MySQL 8.4, RabbitMQ,
Redis and Nacos 3. `deploy/docker-compose.local.yml` additionally starts all
four application services. Copy
`deploy/.env.example` to `deploy/.env`, replace every placeholder, then run:

```powershell
docker compose --env-file .env -f docker-compose.local.yml up --build
```

## Delivery and operations

- GitHub Actions runs `mvn clean verify`, packages all modules, and builds the
  four service images on every push and pull request to `main`.
- Liveness/readiness probes are available at `/actuator/health/liveness` and
  `/actuator/health/readiness`. They are intended for orchestration only;
  application API authorization still uses JWT roles.
- An ADMIN can query `GET /api/v1/admin/operations/outbox-summary` to inspect
  pending and leased Outbox counts without seeing event payloads.

This is a reproducible local topology, not evidence of production capacity or
multi-instance behaviour. Run `mvn clean test` before building images.

## Attendance policy

Resources carry a `noShowGraceMinutes` policy (1-60; default 15). Staff may
record `CONFIRMED -> NO_SHOW` only after `slot.startsAt + noShowGraceMinutes`.
The system does not automatically promote a waitlisted reservation part-way
through an already-started slot: the candidate may no longer be available and
would need an explicit acceptance workflow. Existing resources receive the
default through Flyway V4, so the policy is backwards compatible.
