# CampusReserve learning cursor

This file is the single source of truth for the **four-week** CampusReserve
learning order, including the library study-room V2 extension in Week 4.
Before every lesson, read [teaching-rules.md](teaching-rules.md) and
[project-layout.md](project-layout.md) as well. Each day has three lessons.
Do not skip ahead or rename a lesson without first updating this cursor.

## Development baseline already completed

- Four Spring Boot services: Gateway, Identity, Reservation and Notification.
- Student flow: register, login, browse catalog, reserve, waitlist, cancel and
  automatic waitlist promotion.
- Staff flow: create/publish resources, create non-overlapping slots, inspect
  the work queue, check in, complete or mark a no-show.
- MySQL transactions, Flyway migrations, MyBatis-Plus ordinary CRUD and
  handwritten guarded SQL for capacity and state transitions.
- Transactional Outbox, RabbitMQ publisher confirms, consumer Inbox
  idempotency and student notifications.
- JWT role control, request correlation, optional Redis read cache and local VM
  integration smoke using MySQL/RabbitMQ.
- Explicit JWT issuer validation, stable Asia/Shanghai reservation time rules,
  elapsed-slot closure, liveness/readiness probes and an ADMIN-only Outbox
  backlog summary.
- GitHub Actions verification and four-service container-image build pipeline.

## Week 1 - service boundaries and student reservation correctness

| Day | Lesson 1 | Lesson 2 | Lesson 3 |
| --- | --- | --- | --- |
| Day 1 | Business map and why the split is not screen-based CRUD | Maven multi-module layout and `campus-common` | Gateway routing and the request entry path |
| Day 2 | Identity ownership and account schema | Login, BCrypt hashing, JWT issuer and expiry | Gateway/service JWT verification and role boundaries |
| Day 3 | Reservation/resource/slot schema and Flyway ownership | MyBatis-Plus for ordinary queries | Why capacity updates use guarded handwritten SQL |
| Day 4 | Idempotency-Key and replay semantics | Confirmed versus waitlisted reservation path | Cancellation and atomic waitlist promotion |
| Day 5 | Resource publication and time-slot lifecycle | Overlap prevention: lock + interval query | Pagination, public catalog and response contracts |
| Day 6 | Student end-to-end read/write sequence | Error semantics: 400/401/403/404/409 | MySQL Smoke evidence and its limits |
| Day 7 | Review: draw student reservation chain | Review: answer concurrency scenarios | Review: inspect core files and interview questions |

## Week 2 - staff fulfillment, asynchronous consistency and operations

| Day | Lesson 1 | Lesson 2 | Lesson 3 |
| --- | --- | --- | --- |
| Day 1 | Staff identity and authorization | Work queue ownership and pagination | `CONFIRMED -> CHECKED_IN -> COMPLETED` state machine |
| Day 2 | No-show path and valid terminal states | Optimistic/version and guarded updates | Retry-safe staff commands without duplicate events |
| Day 3 | Why a local transaction cannot directly guarantee broker delivery | Outbox row, lease and publisher confirm chain | `markPublished` versus `releaseAfterFailure` |
| Day 4 | RabbitMQ exchange, queue and routing key | Consumer ACK versus publisher confirm | Inbox table and at-least-once business idempotency |
| Day 5 | Notification ownership and read model | Request IDs and MDC correlation | Liveness/readiness, Outbox backlog and safe management access |
| Day 6 | Redis for advisory cache/optional admission, never quota truth | Nacos deployment option versus verified static routing | Docker/local configuration, CI and secret boundaries |
| Day 7 | Review: full student + staff + event flow | Review: exactly-once misconceptions | Mock, unit and real Smoke verification matrix |

## Week 3 - remaining developer-focused sequence

The learner is targeting Java development roles. Week 3 continues the
developer-focused foundation; the merged Week 4 below finishes the product.
Each day still has three lessons. The current cursor is recorded at the end.

| Developer day | Lesson 1 | Lesson 2 | Lesson 3 |
| --- | --- | --- | --- |
| Day 1 - Identity | Identity user schema, registration and BCrypt ownership | Bootstrap ADMIN versus public registration | ADMIN creates support staff without privilege escalation |
| Day 2 - Notification | Notification read-model schema and ownership | Student notification query, pagination and data isolation | Read/unread state and API response boundary |
| Day 3 - Gateway and errors | Gateway route contract and trusted identity boundary | Shared `ApiResponse` and `BusinessException` contract | Validation, business failures and safe client error messages |
| Day 4 - Security and layering | Cross-service JWT, roles and direct-service access | Controller, Service and Mapper ownership review | Integration-test design for independent services |
| Day 5 - Redis and concurrency | Public-catalog cache boundaries | After-commit invalidation and cache-miss fallback | Legacy slot Lua admission versus MySQL capacity/idempotency truth |
| Day 6 - Verification and closure | Mock/unit-test versus real dependency evidence | MySQL/RabbitMQ Smoke and unproven concurrency boundaries | Full student/staff/notification acceptance chain and interview demonstration |
| Day 7 - Consolidated review | Identity/Gateway/authorization review | Reservation/Outbox/Inbox consistency review | Testing boundaries, architecture navigation and interview questions |

Teaching emphasis after Week 3 Day 6 Lesson 2: the learner has completed the
test-evidence boundary material. Do not add standalone deep dives into test
framework mechanics. Day 6 Lesson 3 should prioritize the complete business
journey and the core production code at each boundary; mention verification
only briefly. Day 7 review should be concise and developer-focused. Do not
claim concurrency throughput, QPS or latency improvements without a separate
repeatable JMeter load test and recorded environment, workload and results.

## Week 4 - completed product journey: fixed slots and library study rooms

Start this week **after** Week 3 Day 7; do not advance the current cursor
because the code has already been developed. The concrete product is library
study-room team booking plus the older fixed-slot venue flow, not a general
campus marketplace. Reuse the previously taught JWT, transaction, Outbox,
Inbox and RabbitMQ foundations instead of teaching them from scratch again.
Each lesson must walk its actual request/event chain and explain core code.

| Day | Lesson 1 | Lesson 2 | Lesson 3 |
| --- | --- | --- | --- |
| Day 1 | Fixed-slot lecture versus study-room journey: lecture title/speaker/description write-read chain, and why `STUDY_ROOM` cannot use legacy quota APIs | V6-V10 Flyway tables: old slot cancellation, lecture slot details, and team/window/interval/snapshot ownership | D-2 midnight release, staff-controlled windows, 30-minute grid and 60-240-minute validation |
| Day 2 | Identity student number, JWT principal and self-reported-number trust limit | Direct student-number invitation and owner-only acceptance/decline | Invite-code join, leader/member limits and consent; forming a team holds no room |
| Day 3 | Booking Controller -> Service -> Mapper and `Idempotency-Key` replay | Room row lock, current-read overlap, MySQL isolation and interval correctness | Accepted-member snapshot and cross-room student bucket uniqueness |
| Day 4 | Exact-interval `CONFIRMED` versus `WAITLISTED` decision | Cancellation and oldest eligible waitlist promotion | Same-room/cross-room races; new room/day Lua admission versus MySQL correctness |
| Day 5 | Staff check-in, completion and no-show for the chosen interval | Legacy slot cancellation versus V2 window close/shorten: audit, affected orders and rollback | Outbox -> RabbitMQ -> Inbox -> per-student notification; failure and redelivery boundaries |
| Day 6 | Public availability, short-TTL window cache and after-commit invalidation | Real MySQL concurrency tests, Redis Lua verification boundary and Chrome-to-notification E2E | Full business walkthrough, truthful résumé claims and unverified production limits |
| Day 7 | Review A: draw consent, booking, staff and maintenance chains and locate code | Review B: transaction, idempotency, waitlist and concurrency scenarios | Review C: Outbox/Inbox, evidence boundaries and interview demonstration |

Week 4 Day 1 Lesson 1 was regenerated with the lecture-specific write/read
chain and the learner's three answers were graded before moving on. Day 1
Lesson 2 covers V10 alongside V6-V9. Do not add a fourth lesson or shift the
study-room sequence.
Day 1 Lesson 2's three answers were graded 3/3. The learner requested the
previously omitted field-level schema detail; give the V6-V10 field supplement
before presenting Day 1 Lesson 3.
Day 1 Lesson 3's three answers were graded 3/3. Proceed to Day 2 Lesson 1.
Day 2 Lesson 1's three answers were graded 2.5/3; the uniqueness-versus-JWT
signature distinction was clarified before moving to Lesson 2.
V10 has unit/API tests but has not yet been verified against real MySQL; do not
include it in the earlier real-MySQL smoke claims.

Week 4 development evidence (2026-10-09): the ordinary Maven test suite
passed; the opt-in real-MySQL study-room smoke ran four tests with zero
failures; a local headless-Chrome run covered team creation, interval booking,
the privacy-safe occupied-interval view, admin maintenance cancellation and
student notification through Gateway, MySQL, Outbox, RabbitMQ and Inbox.
Earlier fixed-slot maintenance and isolated RabbitMQ smokes remain valid for
their own paths. These selected checks do not establish campus SSO, production
multi-instance load, broker-outage recovery or SLA. The student number is
currently self-reported at registration. Do not quote unmeasured QPS/latency
or claim institutional identity verification.

The Redis optimization is an optional, separately configurable room/day and
student burst limiter plus a short-TTL public window cache. MySQL still owns
interval and member correctness. Real VM Redis Lua (including a 60-request
concurrency correctness smoke with a 20-admission ceiling) and window-cache
smokes passed, as did the four real-MySQL cases with V2 Lua enabled and the local
Chrome-to-notification journey with V2 Lua enabled. There is **no measured
throughput or latency improvement yet**. Teach this limit explicitly.

## Topics intentionally removed from this teaching plan

Docker/Compose operations, Nacos deployment practice, liveness/readiness,
Outbox operational monitoring, log/MDC incident response, GitHub Actions and
CI operations are not scheduled lessons.  Their existing code remains in the
repository and may be studied later as optional operations material.

## Current cursor

**Week 1 and Week 2 Day 7 review content was deferred and merged into Week 3 Day 7. At the learner's request, all three Week 3 Day 7 review lessons are skipped; Week 3 Day 7 Lesson 1 was introduced but its questions were not graded.**

Week 4 Day 2 Lesson 2's three answers were graded 2.5/3. Correction: a directly invited member is `INVITED` with `user_reference = NULL` until that student accepts with their own authenticated identity.
Week 4 Day 2 Lesson 3's three answers were graded 3/3. An `INVITED` member is excluded from the accepted-member count, and the team is marked `BOOKED` only during the booking transaction.

Week 4 Day 3 Lesson 1's three answers were graded 2.5/3. Correction: reusing the same idempotency key with different `startsAt` is a 409 conflict (`study-room-idempotency-key-reused`), not a replay.

Week 4 Day 3 Lesson 2's three answers were graded 2/3. Correction: different teams do not wait on one another's `study_team` row; they serialize on `ReservableResourceMapper.lockById(resourceId)`. Without that lock, the error would be two overlapping confirmed rows, not one row overwriting another.

Week 4 Day 3 Lesson 3's three answers were graded 2.5/3. Correction: a two-hour confirmed booking creates four half-hour buckets per accepted member, so three accepted members create twelve rows; the learner correctly listed one member's four bucket times.

Week 4 Day 4 Lesson 1's three answers were graded 3/3. The learner distinguished room overlap (waitlist), member time conflict (409), and booking status from team status.

Week 4 Day 4 Lesson 2's three answers were graded 2/3. Correction: if promotion fails while writing member buckets, the exception rolls back the entire cancellation transaction, including the booking status, released buckets, team state and Outbox row.

Week 4 Day 4 Lesson 3's three answers were graded 3/3. The learner correctly separated room-row serialization, cross-room student-bucket uniqueness, and Redis request admission from MySQL booking correctness.

**Week 4 Day 5 Lesson 1 was completed. The corrected member-attendance flow was taught and its three answers were graded 3/3. Week 4 Day 5 Lesson 2 was completed and its three answers were graded 3/3. Week 4 Day 5 Lesson 3 was completed and its three answers were graded 3/3. Week 4 Day 6 Lesson 1 was completed and its three answers were graded 2.5/3; the booking-time database guard was clarified. Before beginning Day 6 Lesson 2, give the omitted Day 2 team-lifecycle supplement: accepted member leave, leader close, and why BOOKED teams cannot change membership.**

**The omitted Day 2 team-lifecycle supplement was completed and graded 2.5/3; member leave changes `ACCEPTED` to `LEFT` while clearing `user_reference` and `accepted_at`. Active lesson: Week 4, Day 6, Lesson 2 - Real MySQL concurrency tests, Redis Lua verification boundary and Chrome-to-notification E2E.**

**Week 4 Day 6 Lesson 2 was completed and its three answers were graded 3/3. Active lesson: Week 4, Day 6, Lesson 3 - Full business walkthrough, truthful resume claims and unverified production limits.**

**Week 4 Day 6 Lesson 3 was completed and its three answers were graded 2.5/3; the cancellation-to-notification tail was clarified. Automatic no-show handling was implemented for fixed-slot and study-room confirmed reservations. The learner reviewed it independently and explicitly skipped the supplemental lesson. The next lesson is Week 4 Day 7 Review A; do not reintroduce the supplement unless requested.**

During Day 5 Lesson 1, the learner identified that one-person check-in did not
enforce large-room minimum attendance. The implementation now records each
arriving booked member and transitions to `CHECKED_IN` only when the booking's
frozen minimum attendance is reached. Before advancing the cursor, teach this
correction together with its migration and updated API response; replace the
original Lesson 1 questions so they test the corrected behavior.

Week 3 Day 7 was skipped by learner request. There is no separate Week 5 or
second V2 cursor; the remaining planned lessons are in Week 4.

Before each new lesson, read this file and move only the active cursor line
after that lesson is complete.
