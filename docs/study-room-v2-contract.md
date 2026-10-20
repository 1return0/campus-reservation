# Study-room booking V2 — agreed business contract

Status: implemented and locally verified. The study-room V2 uses separate
`STUDY_ROOM` resources and `/api/v1/study-rooms/**` endpoints. Legacy fixed-slot
resources remain available for `MEETING_ROOM`; their write API rejects V2 rooms.
This is a local acceptance result, not a production reliability claim.

## Product boundary

The bookable resource is a library study room, not an equipment item or an
individual seat. A room has a configurable minimum and maximum number of
students. At most one confirmed team can occupy a room during any overlapping
time interval. The old `resource_slot.capacity` is a booking quota, so it must
not be displayed or reused as the room's seating capacity.

## Time and schedule

- For use on calendar date D, booking opens at 00:00 on D−2 in the campus
  time zone. Example: any use interval on 10 October opens on 8 October at
  00:00. This is not a rolling 48-hour window.
- ADMIN and SUPPORT_STAFF configure each room's available use windows. The
  initial/default daily window is 07:30–21:30, but it is not a hard-coded
  global rule. The admin API can create a bounded date range with the same
  daily start/end time; it persists one concrete window per date. Submitting a
  booking can happen at any time after release and
  before the requested interval starts.
- Students select a start and end within one available window, on 30-minute
  boundaries. Duration is 60–240 minutes. The server rechecks every rule at
  write time; the browser's availability view is advisory.
- A schedule edit cannot silently invalidate existing reservations. Staff
  must first run a maintenance cancellation for the affected interval; that
  transaction records the reason and creates an Outbox event for each affected
  student/team. An edit that still conflicts is rejected.

## Team formation and booking

- A student leads a team. Direct-entry mode adds invited student numbers;
  invite-code mode lets a signed-in student join. Both modes lead to the same
  membership state: invited/requested → accepted, or declined/left.
- A student number must uniquely identify an active student account. Only the
  signed-in owner of that number may accept its membership; merely typing a
  number never counts as consent. Duplicate numbers and the same account
  entered twice are rejected.
- Creating/inviting a team never holds a room. The leader may book only after
  the number of accepted members (including the leader) meets the selected
  room's minimum and does not exceed its maximum. The booking transaction
  checks availability again; a room may have been taken while the team formed.
- Before booking, members may leave or be replaced. Once a booking succeeds,
  its participant list is snapshotted and frozen. The leader must cancel and
  rebook to change that list. A member may not participate in two confirmed
  bookings with overlapping intervals. Waitlist promotion must recheck this.
- The booking request keeps its idempotency key. A replay returns the same
  result; it may not create a second team booking or emit another event.

## Availability, waitlist and fulfilment

- Intervals use half-open overlap semantics: `[start, end)`. Two intervals
  conflict iff `existing.start < requested.end` and
  `existing.end > requested.start`; adjacent intervals are allowed.
- MySQL has no native exclusion constraint for interval overlap. Every create,
  cancel, promotion, maintenance cancellation and schedule mutation for one
  room must serialize through the same room row lock. The overlap query and
  insert happen inside that transaction. Redis may shed excess requests, but
  is not the correctness boundary.
- A waitlist entry records its exact requested interval and accepted-member
  snapshot. When time becomes available, inspect waiting entries in stable
  `(created_at, id)` order and promote the earliest *eligible* one whose whole
  interval fits. Revalidate membership and overlapping participant bookings;
  an ineligible entry cannot block all later candidates indefinitely.
- Staff record each arriving student against the frozen participant list, not
  against the team's mutable membership. The required attendance count is
  frozen from the room's minimum when booking. A booking remains `CONFIRMED`
  until that many distinct participants have actually checked in before the
  chosen start plus the room's no-show grace period; only then does it become
  `CHECKED_IN` and emit one check-in event. Additional booked participants may
  check in before the interval ends without duplicating that event. If the
  minimum is not met after the grace period, staff may mark the booking
  `NO_SHOW` (including partial attendance). A no-show after the interval has
  started does not promise a useful waitlist promotion.

## Required implementation and acceptance work

1. Identity migration: unique student number for STUDENT accounts, registration
   and JWT/principal ownership boundary for membership acceptance.
2. Reservation migration: room occupancy limits, configurable opening windows,
   team/member tables, exact reservation intervals and participant snapshots.
   Backfill old fixed-slot orders without inventing team consent.
3. Replace fixed-slot quota decisions with room-lock/interval decisions;
   refactor waitlist, check-in/no-show, maintenance cancellation, Outbox payload
   and public availability responses together. Remove or explicitly reject
   obsolete write endpoints so they cannot bypass V2 rules.
4. Update the browser for team invitation/code acceptance, calendar/interval
   selection, member list and conflict messages.
5. Verify boundary times (D−2 00:00; 07:30/21:30), 60/240-minute limits,
   group limits and consent, overlapping/adjacent intervals, concurrent
   booking, cancellation/promotion, maintenance notifications and one complete
   Chrome → Gateway → MySQL → RabbitMQ → Inbox → student result flow.

## Acceptance evidence and remaining boundary

- The opt-in `StudyRoomLiveSmoke` exercises real MySQL migrations, consent,
  booking and idempotency, waitlist promotion, maintenance cancellation, and
  same-room/cross-room concurrent booking. It does not start RabbitMQ delivery.
- `deploy/smoke/study-room-end-to-end.ps1` exercises Chrome, Gateway/JWT,
  MySQL, Outbox, RabbitMQ, Inbox and the student's notification view on the
  local VM stack. The run isolates its RabbitMQ topology and cleans test data.
- Public availability returns windows and occupied intervals without student
  identities. It is advisory; booking always rechecks under database locks.
- Optional Redis Lua admission limits new attempts per room/use-date and per
  student/room/use-date. The separate V2 switch defaults off; an idempotent
  replay returns before admission. This never grants a booking or replaces
  MySQL interval/member checks. Open-window read caching is separately
  switchable with a short TTL and after-commit invalidation. Occupied intervals
  are not cached. VM Redis Lua and window-cache smokes passed; MySQL and browser
  journeys passed with V2 Lua enabled. Neither optimization has a measured
  performance claim yet. Cache read errors fall back to the database and
  optional cache invalidation errors cannot turn a committed write into a
  reported booking failure; the TTL bounds stale advisory reads.
- Registration currently accepts a self-reported student number. A real campus
  deployment requires authoritative student identity provisioning or SSO before
  treating that number as an institutional fact.
- Local smoke is not high-load, multi-instance, broker-failure or production
  acceptance. Those properties must not be claimed from the above runs.
