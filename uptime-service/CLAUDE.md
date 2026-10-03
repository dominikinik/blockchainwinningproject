# CLAUDE.md — uptime-service

Keep this module's documentation in step with behavior, architecture, configuration, endpoints, and commands.

## Overview

Spring Boot 4.1 / Java 21 modular monolith with **checking**, **aggregation/history**, and **tracking lifecycle** modules in one Maven build/process. Checks target a 10 second cadence. Consecutive equivalent failures become typed `BadEvent` entities; session-scoped one-minute `UptimeEvent` summaries reference their IDs. There is no `GoodSubEvent` and no per-check failure list in new persisted summaries.

**Tracking starts only on an explicit user command. Health failures, HTTP errors, unreachable endpoints, aggregation window boundaries, and buffer pressure NEVER stop tracking. Only the user's stop command ends a live session.** Process restart is separate: it cannot recover an in-memory session and marks prior open persisted sessions interrupted rather than inventing offline observations.

Build on the host machine; the shared devcontainer has no JDK.

## Commands and migrations

Running the service needs PostgreSQL; the default full test suite does not:

```bash
docker compose -f ../uptime-db/docker-compose.yml up -d --wait
```

Existing databases need migrations **001 and 002**, in order, applied to both `uptime` and `uptime_test`. See `../uptime-db/CLAUDE.md` for non-destructive commands. Init scripts run only on empty data directories. Do not delete history to change the schema. New storage does not invent sessions or backfill old observations.

From `uptime-service/` (`mvnw.cmd` in PowerShell):

```bash
./mvnw spring-boot:run
./mvnw test                                # full portable suite; in-memory H2, no PostgreSQL
./mvnw test -Dtest=AggregateChecksTest       # single application test class
./mvnw test -Dtest=TrackingServiceTest
./mvnw package                             # build jar and run the portable suite
```

PostgreSQL suites (isolated JDBC and full Boot persistence/transaction tests) are opt-in on POSIX shells:

```bash
RUN_PERSISTENCE_POSTGRES_TESTS=true ./mvnw test -Dtest=JdbcUptimeEventStorePostgresTest,UptimeServiceApplicationPostgresTest
```

On PowerShell, set environment variable `RUN_PERSISTENCE_POSTGRES_TESTS` to `true`, then run the test with `mvnw.cmd`. Use a dedicated migrated `uptime_test` database without concurrent service writers: startup recovery deliberately interrupts persisted open sessions.

## Architecture and boundaries

All packages are under `com.example.uptime`:

- **`checking/domain`**: immutable `CheckResult`, `CheckFailure`, `ProbeResult`, outcome and failure-type enums. No tracking, aggregation, Spring, or JPA dependency. Check IDs remain transient for deduplication; successful checks do not become independent entities.
- **`checking/application`**: plain `ExecuteCheck`, `HealthProbe`, and output `CheckResultSink` ports. Capture injected-clock timestamps and independently monotonic elapsed duration. Unexpected probe exceptions become sanitized check failures; sink errors propagate.
- **`checking/infrastructure`**: local `ApplicationStateHealthProbe` or configurable `HttpHealthProbe`. Classification happens here, before aggregation. No database access.
- **`aggregation/domain`**: `UptimeWindow` aggregate, immutable `UptimeEvent`, `BadEvent`, `FailureKey`, `TimeRange`, and status/type enums. Plain Java, no Spring/JPA. Healthy coverage is implicit; unknown intervals are explicit value objects, not successful sub-events.
- **`aggregation/application`**: `AggregateChecks` handles ordered admission, run grouping, coverage, bounded finalization, and retrying finalized events through `UptimeEventStore`. `UptimeQueryService` reads through `UptimeHistoryReader` and validates coverage through `TrackingCoverage`; it does not depend on persistence entities.
- **`aggregation/infrastructure/persistence`**: JDBC writes/reads and lossless JSON mapping. The retained JPA `UptimeRecord` / repository are legacy-only; strict session queries never use that table.
- **`tracking/domain`**: session identity/bounds/status and explicit `START` / `STOP` event records.
- **`tracking/application`**: plain `TrackingService` coordinates lifecycle commands, admission, and finalization via `TrackingStore`. It implements query coverage and overlays an accepted in-memory stop cutoff while its write is pending.
- **`tracking/infrastructure`**: transactional `JdbcTrackingStore`; startup recovery listens for `ApplicationReadyEvent` through the Spring transaction proxy.
- **`monitor`**: composition, scheduling, and aggregation/lifecycle health. No business aggregation rules in schedulers.
- **`state`**: logical application UP/DOWN switch; this is independent of tracking and does not stop the process.
- **`web`**: application-state, tracking, and uptime HTTP adapters.

No message broker, event sourcing, or successful-sub-event entity is introduced. Time comes from injected `Clock`; do not call `Instant.now()` in production code. A within-check backward clock step clamps observation time to its start; late/out-of-order results are rejected and counted rather than changing finalized history.

## Failure classification and grouping

| Observation | Classification |
| --- | --- |
| HTTP 200 with recognized healthy payload | Successful check; no bad entity |
| HTTP 200 explicitly reporting unhealthy state | `DOWNTIME` |
| Non-200, timeout, network/TLS error, interruption, malformed/unrecognized health payload | `CHECK_FAILURE` |
| Local non-UP application health | `DOWNTIME` for the local simulation |

A request failure is not confirmed AWS downtime. HTTP 200 alone is not health evidence. No vendor-specific AWS response contract is assumed.

Merge only adjacent observations within one session with matching **type + code + stable reason**. Dynamic diagnostic messages are not grouping keys. Different HTTP statuses, transport error codes, unhealthy reasons, success between failures, or an observation gap start a new run. Messages are bounded to 512 characters. Reasons up to 256 characters remain literal; longer reasons become SHA-256 identities instead of truncated prefixes, so different errors do not merge merely because their prefixes match. Configure only non-sensitive stable reason fields.

A run is fragmented at one-minute parent boundaries. Each fragment has its own stable UUID, parent/session identity, coverage start/end, first/last actual observation timestamps, observation count, failure key, and bounded diagnostic summary. A continued fragment may have zero new observations and carry earlier observation timestamps. Parent IDs and child IDs are generated once, retained across retries, and not regenerated by persistence.

## Coverage and aggregation

- Sessions and stored coverage use half-open `[start,end)` intervals. UTC buckets are one minute, but first/last parent coverage is clipped to exact session bounds. `(sessionId,bucketStart)` distinguishes multiple sessions in the same UTC minute.
- `UptimeWindowPolicy` defines UTC minute alignment and bounds for aggregation, domain validation and query assembly. Nominal counts are per minute (six at the default cadence), prorated for clipped coverage. Flush remains a separate 1 second persistence job; it does not finalize an unfinished minute.
- Infer the last observed outcome forward only until the next result or `max-observation-gap-ms`, whichever comes first. Further time is UNKNOWN. The default 50,000 ms gap retains the previous five-interval tolerance (formerly 50 ms at 10 ms sampling), not unlimited availability evidence. An initial period before the first check is also unknown. A same-error observation after a gap opens a new bad run.
- For example, success at 0/10 seconds followed by failure at 20/30 seconds and success at 40 seconds produces a bad run `[20s,40s)`. No successful entities are created. Healthy intervals are derivable from parent/session coverage minus bad and unknown intervals, not from the absence of bad events alone.
- `FAILED` takes precedence whenever a parent contains a bad fragment, including zero-duration failed observations. Otherwise any unknown interval gives `UNKNOWN`; fully inferred healthy coverage gives `SUCCESS`. Counts are observation counts, not duration or proof of uninterrupted availability.
- `partialCoverage` flags clipped bounds, unknown intervals, or fewer observations than nominal cadence for the actual interval. Best-effort 10 second scheduling does not guarantee exactly six checks/minute or evenly spaced samples.
- Finalize whole elapsed minutes during normal tracking. A user stop closes the exact partial tail. At buffer capacity, finalization progresses in bounded chunks over later flushes without dropping gaps or stopping tracking. New observations requiring unavailable capacity are explicitly rejected and counted; retained coverage after the last admitted result expires into unknown.
- A failure timestamp is when a failure was observed, not exact physical outage onset. Nanosecond string precision is not a promise of nanosecond clock accuracy.

## Tracking lifecycle

1. Initially stopped: scheduled sample calls do nothing and make no probe requests.
2. `start()` commits a session and an explicit `START` event before enabling admission. An ambiguous failed start keeps both UUIDs so another start command retries the same record instead of creating a duplicate. Start conflicts with an active/pending-stop session or a backward clock before the preceding stop.
3. `sample()` captures the result and delivers it inside the coordinator's admission gate. Failure classification never changes session state.
4. `stop()` records an immutable cutoff, disables admission, and creates an explicit `STOP` event. State is `STOPPING` until lifecycle intent, all bounded finalization, and all pending event writes complete. Stop persistence trouble retains the same intent/UUID for flush retries and does not resume checks. HTTP 202 means accepted, not fully committed; a pending stop remains volatile until its database write succeeds.
5. `flush()` finalizes under the admission gate but performs database writes outside it. It writes pending stop intent, commits parent/child event batches, and marks the session `STOPPED` only after no unfinalized or pending data remains. A new start must wait for that completion, even when it is in the same second.
6. Repeated start/stop commands in invalid states return conflicts. A zero-length session still has explicit START/STOP records, but no fabricated successful coverage.
7. Startup recovery marks persisted ACTIVE/STOPPING sessions `INTERRUPTED`, ending coverage at their committed frontier or start if none was committed. It creates a recovery STOP only if no user STOP exists; user STOP records are retained. It never auto-resumes tracking.

The lifecycle gate serializes control and persistence commands, while the admission gate protects observation/delivery versus start/stop/finalization. Slow event persistence does not hold the admission gate and cannot block ongoing sampling. Probes do hold it for the bounded check duration, so long-running checks delay control/finalization until completion. Control commands can also wait for an already-running lifecycle/persistence command.

## Storage and reliability

`uptime-db` owns schema/init/migrations/data. New tables are `tracking_session`, `tracking_event`, `uptime_event`, and `bad_event`. A bad row references its parent/session by foreign key; parent response IDs are derived from these rows, not duplicated in a stored ID array. Parent selection returns ordered child IDs in one SQL statement, avoiding per-window database round trips. Query assembly groups by session/bucket rather than repeatedly scanning the whole range.

Exact instants are ISO TEXT/JSON strings. Indexed epoch-second columns only select conservative candidates; final checks use original `Instant` values. No PostgreSQL microsecond rounding occurs at strict session boundaries. Both JDBC and legacy Hibernate serialization share Boot's Jackson 3 persistence mapper.

A finalized parent, its bad children, and session committed progress are one transaction. Identical UUID retries are idempotent; conflicting history fails and rolls back the batch. Never overwrite finalized data. Session locks coordinate writes, stops, and recovery. Writes are acknowledged only after the proxied transaction returns successfully.

Pending events use bounded in-memory storage, oldest-first batches, and exponential retry backoff. Lifecycle intent retries occur on flush. Database failures do not stop tracking. Health exposes active state, open/pending windows, rejection/late/out-of-order/capacity counts, retry details, and sanitized lifecycle failure types. Persistence failure health recovers after successful writes; irrecoverable rejected-observation counts remain DOWN until restart.

**This is not crash-durable ingestion.** Open/pending events and uncommitted lifecycle intent can be lost on exit; a durable journal/queue is needed for stronger recovery. Do not restart merely to clear health counters without considering pending data. One process/writer and one monitored target are supported; independent instances require ownership/coordination.

No schema migration is required for minute windows: indexed epoch seconds are bounds, not bucket-size constraints. Existing strict one-second parents remain immutable and readable; range assembly groups them into candidate minutes but returns their original bounds/identity rather than rewriting or merging history. Legacy `uptime_record` and migration 001 are preserved. Strict endpoints do not infer sessions or healthy/bad runs from legacy history. JPA validates the retained legacy entity; strict JDBC tables require migration 002 and are accessed by startup recovery.

Connection overrides: `UPTIME_DB_URL`, `UPTIME_DB_USER`, `UPTIME_DB_PASSWORD`; opt-in PostgreSQL tests use `UPTIME_TEST_DB_URL` (default local `uptime_test`). The default `test` profile uses in-memory H2.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `uptime.sample-interval-ms` | 10000 | Target check cadence |
| `uptime.flush-interval-ms` | 1000 | Fixed delay between finalize/persist runs |
| `uptime.scheduling-enabled` | true | Disable automated jobs for deterministic tests |
| `uptime.max-buffered-windows` | 3600 | Open plus pending parents in memory |
| `uptime.persistence-batch-size` | 60 | Maximum committed parents per attempt |
| `uptime.retry-initial-ms` / `retry-max-ms` | 1000 / 30000 | Event persistence backoff bounds |
| `uptime.max-observation-gap-ms` | 50000 | Maximum carry-forward from the last observation |
| `uptime.default-range-seconds` / `max-range-seconds` | 300 / 86400 | Default/maximum range |
| `uptime.probe.url` | unset | Optional HTTP(S) target; unset uses local state |
| `uptime.probe.timeout-ms` | 1000 | HTTP connect/request/full-response deadline |
| `uptime.probe.status-field` | status | Literal top-level JSON key, not a dot path |
| `uptime.probe.healthy-value` / `unhealthy-value` | UP / DOWN | Exact scalar text health values |
| `uptime.probe.reason-field` | unset | Optional stable, non-sensitive scalar error identity |

Root intervals/limits must be positive; default range cannot exceed max, retry max cannot be below initial. HTTP URL must be HTTP(S), with no credentials/fragment. Status values must be distinct. The remote adapter rejects redirects and limits HTTP 200 bodies to 64 KiB; invalid, missing, or unrecognized configured fields are check failures. HTTP response bodies, arbitrary exception messages, and secrets are not exposed in diagnostics.

A remote URL sends checks to that host at the configured cadence while tracking is active; consider its rate limits/load/cost. Checks are serial and the 10 second cadence is best-effort; slow probes can delay sampling. The local logical-health switch does not affect a configured remote target.

## HTTP API and compatibility changes

- `POST /api/tracking/start`: 201, explicit persisted START, enables tracking.
- `POST /api/tracking/stop`: 202, explicit STOP intent, immediately stops admitting new checks; state may remain STOPPING while writes drain.
- `GET /api/tracking/state`: state/session bounds/committed frontier; before first start returns STOPPED with null identity.
- `GET /api/tracking/events?sessionId=...`: explicit START/STOP records; an accepted uncommitted STOP intent is visible while state is STOPPING.
- `POST /api/application/start` / `stop`: change local logical health only, not tracking or process lifecycle.
- `GET /api/uptime/at?time=...`: session-specific parent summary containing the ORIGINAL timestamp. `down=true` for FAILED, false for SUCCESS, null for UNKNOWN/PENDING. This is the parent-window result, not an assertion that every instant within a failed parent was down.
- `GET /api/uptime/event?time=...`: parent/session IDs, bucket and actual bounds, counts, status, partial coverage, `badEventIds`, typed `badEvents`, and `unknownIntervals`.
- `GET /api/uptime?from=...&to=...`: inclusive ORIGINAL bounds; reject any untracked gap before reading data. Multiple points may share a UTC minute when sessions start/stop within it. Explicit bounds are not truncated or silently clamped. Range returns matching minute parents (or UNKNOWN/PENDING minute placeholders), not one point per second. Range limits/defaults remain measured in seconds, independently of window size. Default range uses latest committed session coverage, or the session containing an explicit `to` when `from` is omitted.
- Before tracking start, between stopped/new sessions, or at/after exclusive stop: 422 ProblemDetail code `OUTSIDE_TRACKING_COVERAGE` with uncovered bounds. Tracked missing data is UNKNOWN/PENDING, **not an outside-tracking error or fabricated downtime**. Future/reversed/oversized ranges or absent default committed history return 400. Invalid lifecycle commands return 409 `TRACKING_CONFLICT`. Database failures return sanitized 503.
- `/actuator/health` reflects local application/database/aggregation health, but does not control tracking. It is not the composite health sampled by the local probe, avoiding a persistence feedback loop.
- Swagger UI `/swagger-ui.html`; OpenAPI `/v3/api-docs`.

These are intentional API changes: legacy missing-as-down, raw failure lists, and timestamp-only event identity no longer apply.

## Testing

Database-free domain/application/controller/serializer/wiring tests cover homogeneous runs and type/error splits, boundaries/carry/gaps, explicit lifecycle records, no checks before start, continuous tracking through all failure types, stop retries, partial/same-second sessions, bounded drain/retries, concurrency during slow writes, exact query bounds, and HTTP errors. Controlled clocks replace sleeps; concurrency waits are bounded.

The default full suite (`./mvnw test`, also run by `scripts/test-all.sh` and the pre-commit hook) must remain portable and deterministic, without a running database or other module. `UptimeServiceApplicationTests` runs the full Boot context with MockMvc using in-memory H2 in PostgreSQL mode (`src/test/resources/application-test.properties`), disabled schedulers and controlled Clock/HealthProbe inputs. Preserve coverage of explicit lifecycle commands, typed events, exact query bounds, UNKNOWN/PENDING results, health-control independence, conflicts, retries and rollback; do not restore legacy missing-as-down behavior. Keep `src/test/resources/schema.sql` synchronized with the legacy entity and the H2-compatible schema needed by startup recovery and strict JDBC adapters.

`JdbcUptimeEventStorePostgresTest` and `UptimeServiceApplicationPostgresTest` are skipped unless `RUN_PERSISTENCE_POSTGRES_TESTS=true`. The former uses an isolated JDBC Spring context; the latter exercises actual Boot persistence/transaction proxies with disabled schedulers and mocked Clock/HealthProbe inputs (real ExecuteCheck, aggregation and TrackingService). Against dedicated migrated `uptime_test`, these cover PostgreSQL JSONB, constraints, nanosecond boundaries, UUID retries, whole-batch/child rollback, committed progress, strict coverage, legacy preservation and recovery. Cleanup drains owned in-memory sessions and deletes only owned fixtures in FK order. Never run recovery tests against a shared production database.

Minute fixtures retain the original classification, boundary, gap, retry, capacity and lifecycle regressions with explicit test cadence/gap values. Dedicated default-cadence tests cover six checks/minute, the exclusive minute edge and expiry after five intervals; queries cover inclusive range edges and reading historical second parents.

Add tests with every feature or bug fix in the matching layer, including startup, invalid input, empty/missing data, boundaries and error paths. Use injected or mutable clocks instead of sleeping and mock remote modules/network at their boundaries.
