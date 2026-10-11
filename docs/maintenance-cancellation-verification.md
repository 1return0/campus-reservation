# Maintenance cancellation verification — 2026-10-08

## Scope and evidence

| Check | Result | What it proves |
| --- | --- | --- |
| `mvn clean package` with Java 17 | Passed: 42 default tests, 0 failures/errors, 1 skipped | All runnable default module tests and runnable JAR packaging. The skipped Testcontainers test requires a local Docker daemon; separate VM smokes ran below. |
| Reservation service startup against VM MySQL 8.0 | Passed | Flyway validated V1–V6; V6 applied; service reached ready state with Outbox delivery and slot lifecycle schedulers disabled. |
| `SlotCancellationLiveSmoke` against VM MySQL | Passed, 3 tests | Two simultaneous same-operator cancellation calls converged to one durable result. One confirmed and one waitlisted order became `CANCELLED_BY_ADMIN`; waitlist row cancelled; two Outbox rows and one audit row committed; replay did not add events. Further tests raced student booking and student cancellation against admin cancellation and checked the terminal order and Outbox invariants. |
| `MaintenancePublisherLiveSmoke` against VM RabbitMQ | Passed, 1 test | Actual Publisher received a correlated broker confirm and its new event was routed to an isolated temporary queue. |
| `MaintenanceNotificationLiveSmoke` against VM RabbitMQ and MySQL | Passed, 1 test | Actual listener consumed the new event from an isolated queue and wrote one Inbox row and one student notification. |
| `deploy/smoke/maintenance-end-to-end.ps1` with headless Chrome, four local services and VM MySQL/RabbitMQ | Passed, run `dca7c58e8579` | Student booked in the Gateway browser page; ADMIN cancelled the slot in that page; student saw `CANCELLED_BY_ADMIN` and the notification in the page. The event was `PUBLISHED` in Outbox and present once in Notification Inbox. |
| Isolated test cleanup | Passed | Four started services stopped, temporary RabbitMQ exchange/queues were deleted through AMQP, and the generated student/resource/notification records for run `dca7c58e8579` were absent afterward. |
| Browser entry test | Passed | Gateway serves `/`, `/index.html` and `/app.js` without a token. |
| JavaScript syntax | Passed | `node --check` on the browser client. |

The slot creation check, elapsed-slot scheduler and maintenance-cancellation
predicate now compare against the same configured reservation `Clock`, avoiding
an application/MySQL time-zone mismatch in these paths.

All three live smoke suites use distinct generated identifiers. They removed only their
own test rows; the RabbitMQ smokes also removed their own temporary queues and
exchanges. No existing outbox delivery scheduler or production queue was
started by these smokes.

## Not proven yet

- The full browser-to-notification maintenance path above is proven for one
  isolated run, not for every resource/role/error journey.
- Parallel admin cancellation versus student booking and student cancellation
  has been tested with two threads and real MySQL in one process.
  Multi-instance concurrency has not been separately measured.
- Staff fulfilment and unrelated browser journeys were not included in this
  maintenance-specific browser acceptance run.
- No QPS, P95/P99, failover, high-availability or production claim follows
  from the above checks.
- The Testcontainers MySQL test is skipped on the Windows development host
  because no local Docker daemon is available. The opt-in VM MySQL smoke was
  run separately; the two are not the same test result.

The browser run revealed and fixed two real defects before passing: form
handlers used `event.currentTarget` after `await`, and numeric Snowflake IDs
lost precision in JavaScript. Public ID response fields are now JSON strings;
the exact-value contract has regression tests.

## Re-run notes

Use Java 17. Live smoke classes are deliberately excluded from the default
Surefire naming pattern; opt in with `-Dtest=<ClassName>` and the matching
`CAMPUS_RESERVE_LIVE_SMOKE=true` or
`CAMPUS_RESERVE_RABBIT_LIVE_SMOKE=true` environment variable. Supply database
and RabbitMQ environment variables from the local, untracked `deploy/.env`
without committing secrets. Never enable the normal Outbox Scheduler just to
run these isolated smoke tests.

The combined browser smoke checks that no old Outbox event is pending before
temporarily enabling its scheduler, uses uniquely named RabbitMQ topology,
and removes only its generated data and topology afterward. It requires local
Chrome, Java 17, `mysqlsh`, Maven and the configured VM services. AMQP cleanup
uses the application account because it lacks RabbitMQ Management HTTP access.
