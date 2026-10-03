# CLAUDE.md — uptime-db

This file provides guidance to Claude Code (claude.ai/code) when working in the `uptime-db` module. Keep it up to date: if a change alters the schema, databases, credentials, ports, or commands, update this file in the same change.

## Overview

The local PostgreSQL 17 database for `uptime-service`, run with Docker Compose (`postgres:17-alpine`, container `uptime-db`). This module owns the schema and the data files. The service only validates against the schema (`ddl-auto=validate`) and never creates tables itself.

## Commands

Run these from the repo root (or drop the `-f` path when running from `uptime-db/`):

```bash
docker compose -f uptime-db/docker-compose.yml up -d --wait   # start; waits for the pg_isready healthcheck
docker compose -f uptime-db/docker-compose.yml down           # stop (data is kept)
docker exec -it uptime-db psql -U uptime -d uptime            # SQL shell
```

## Non-destructive migration

Existing databases must be migrated before starting the updated service. **Do not delete `data/`: that destroys uptime history.** Init scripts only run on an empty data directory; restarting the container does not apply schema changes. Pause service writers during migration and take your normal database backup first.

From the repository root, using host `psql` (password is prompted; local default is `uptime`):

```bash
psql -h localhost -p 5432 -U uptime -d uptime -W -v ON_ERROR_STOP=1 -f uptime-db/migrations/001-uptime-event-details.sql
psql -h localhost -p 5432 -U uptime -d uptime_test -W -v ON_ERROR_STOP=1 -f uptime-db/migrations/001-uptime-event-details.sql
```

Alternatively, use the container's `psql` (works in Windows PowerShell too; no shell input redirection required):

```bash
docker cp uptime-db/migrations/001-uptime-event-details.sql uptime-db:/tmp/001-uptime-event-details.sql
docker exec uptime-db psql -U uptime -d uptime -v ON_ERROR_STOP=1 -f /tmp/001-uptime-event-details.sql
docker exec uptime-db psql -U uptime -d uptime_test -v ON_ERROR_STOP=1 -f /tmp/001-uptime-event-details.sql
```

If already inside the container, run `psql -U uptime -d uptime -v ON_ERROR_STOP=1 -f /tmp/001-uptime-event-details.sql` and the same command with `-d uptime_test` after copying the file. Each database migration is transactional and rerunnable; existing rows and their timestamps/counts remain unchanged, with both new fields null. No migration is automatically applied by the service.

## Layout

- `docker-compose.yml` defines the database: port 5432, user, password and database all `uptime`, and `restart: unless-stopped`.
- `init/` is mounted read-only as `/docker-entrypoint-initdb.d`. Postgres runs these scripts in name order, and **only when `data/` is empty**, so editing them does not change an existing database.
  - `01-databases.sql` creates the `uptime_test` database, which the service's integration tests use.
  - `02-schema.sh` creates the unchanged legacy `uptime_record` and the four strict tracking/history tables in both databases. Its new-table SQL mirrors migration 002; migrations are not mounted, so fresh init embeds that SQL.
- `migrations/` contains explicit, non-destructive upgrades for existing databases; it is not mounted or automatically executed.
- `data/` holds the Postgres data files. It is gitignored. Uptime history lives here and survives container restarts.

## Schema

`uptime_record` has one row per second:

- `ts TIMESTAMP(6) WITH TIME ZONE PRIMARY KEY`: UTC second start; end is implicitly start + one second.
- `up BOOLEAN NOT NULL`, `samples INTEGER NOT NULL`, `up_samples INTEGER NOT NULL`: existing query API and legacy writers remain compatible.
- `partial_coverage BOOLEAN NULL`: true for incomplete coverage; null means legacy coverage details are unavailable.
- `failures JSONB NULL`: typed failure-observation array; `[]` for a new success, populated for observed failures, null for legacy rows. Each observation has `checkId`, `startedAt`, `observedAt`, `durationNanos`, `code`, `message`. Times are ISO-8601 JSON strings preserving nanoseconds, not PostgreSQL timestamps. Check constraints require an array when present and require both detail columns to be null or both present.

This table is legacy only. Strict JDBC history neither reads nor writes it: missing data is not fabricated downtime, and no sessions or history are backfilled. Migration 001 and all legacy rows remain unchanged. The retained JPA entity/repository validate/read legacy storage only, with untyped legacy failure maps.

## Strict tracking/history schema (migration 002)

Apply `002-tracking-and-bad-events.sql` to **both** databases after 001, using the same host commands above with the 002 filename, or:

```bash
docker cp uptime-db/migrations/002-tracking-and-bad-events.sql uptime-db:/tmp/002-tracking-and-bad-events.sql
docker exec uptime-db psql -U uptime -d uptime -v ON_ERROR_STOP=1 -f /tmp/002-tracking-and-bad-events.sql
docker exec uptime-db psql -U uptime -d uptime_test -v ON_ERROR_STOP=1 -f /tmp/002-tracking-and-bad-events.sql
```

002 is transactional and rerunnable (`CREATE ... IF NOT EXISTS`); it never alters `uptime_record` or copies its rows.

- `tracking_session`: UUID PK; exact `started_at`, nullable `stopped_at`, nullable `committed_through` as ISO TEXT; checked status ACTIVE/STOPPING/STOPPED/INTERRUPTED; indexed BIGINT `start_second`/nullable `stop_second`. A partial unique index permits only one open ACTIVE **or STOPPING** session, preventing a new start until pending stop commits finish.
- `tracking_event`: UUID PK, session FK, checked START/STOP type, lossless JSONB event payload; unique `(session_id,type)`. No fabricated START events.
- `uptime_event`: UUID PK, session FK, ISO TEXT `bucket_start`, indexed BIGINT start/end seconds, JSONB immutable parent payload (all record fields except `badEvents`). Unique `(session_id,bucket_start)` allows different sessions in the same second.
- `bad_event`: UUID PK, `(uptime_event_id,session_id)` composite FK to the parent, per-parent unique ordinal and JSONB typed bad-event payload. Child IDs are derived from these FK rows in ordinal order; there is no duplicate child-ID array or join table.

All instants, including unknown intervals and bad-event observation boundaries, are lossless ISO strings using Boot's Jackson 3 persistence mapper. Seconds are conservative indexed candidate bounds only; readers perform exact Java Instant filtering with half-open `[start,end)` coverage. No PostgreSQL timestamp rounding occurs at strict boundaries.

`JdbcUptimeEventStore.saveAll(List<UptimeEvent>)` locks affected sessions in UUID order and inserts parents/children plus monotonic `committed_through` atomically. UUID retries compare immutable JSONB parent payload and ordered typed children; changed duplicates fail and roll back the batch. Nothing is overwritten or orphaned. New rows cannot extend beyond session edges or be appended after STOPPED/INTERRUPTED. Updates touch only committed progress, never stale start/stop fields.

`JdbcUptimeHistoryReader` implements `List<UptimeHistoryEntry> at(Instant)`, `range(Instant,Instant)` and `List<BadEvent> badEvents(UUID)`, exclusively against the new tables. Range uses inclusive original query bounds `[from,to]`, including point ranges (`from == to`), against half-open stored windows: `windowStart <= to && windowEnd > from`. At selects precise containing windows. Results include every matching session and are ordered by window start/session/event ID. Parent selection retrieves ordered child UUIDs in the same SQL statement, avoiding one database round trip per second; detailed child payloads are loaded separately only for event-detail responses.

`JdbcTrackingStore` implements exactly `saveStart(TrackingSession,TrackingEvent)`, `saveStop(TrackingSession,TrackingEvent)`, `completeStop(UUID)`, `find(UUID)`, `latest()`, `sessions(Instant,Instant)`, `events(UUID)`, `recoverInterrupted()`. Start/stop and explicit lifecycle events are atomic; retries cannot overwrite committed progress or regress STOPPED. Session selection likewise accepts inclusive original bounds, including points: `startedAt <= to && stoppedAt > from` (or no stop). Only reversed bounds return empty immediately. Stop retries validate the immutable start as well as the stop/event parameters, but deliberately ignore stale committed snapshots. `completeStop` locks the session, rejects ACTIVE/INTERRUPTED, permits idempotent STOPPED retries, and requires committedThrough to reach stoppedAt for nonzero sessions; zero-length stops need no committed data. Caller must still ensure pending aggregation is zero: a monotonic maximum committed endpoint is not proof that every earlier pending event was saved. Recovery runs on ApplicationReadyEvent and locks all open sessions: ACTIVE/STOPPING become INTERRUPTED with cutoff at committedThrough, or start when nothing was committed. An explicit STOP with reason PROCESS_INTERRUPTED is inserted only if no user STOP exists; an existing user STOP and its original time/reason are retained. INTERRUPTED status diagnoses recovery even when a user STOP exists. Recovery never resumes monitoring or records process-offline downtime.

Keep fresh init, migrations and persistence adapters in step. Apply migrations to **both** databases instead of recreating history. JPA validation covers the retained legacy entity; strict JDBC tables require the migrations and are also accessed by startup recovery.

## Persistence validation

From `uptime-service/`, focused unit tests run with `./mvnw test -Dtest=JdbcUptimeEventStoreTest,JdbcUptimeHistoryReaderTest,JdbcTrackingStoreTest,JdbcTrackingStoreProxyTest`. The proxy tests publish ApplicationReadyEvent through an actual Spring interface transaction proxy and check both JDBC commit and rollback, without PostgreSQL. Real PostgreSQL tests are isolated from application schedulers and use their own JDBC Spring context. After migrating `uptime_test`, opt in on a POSIX shell:

```bash
RUN_PERSISTENCE_POSTGRES_TESTS=true ./mvnw test -Dtest=JdbcUptimeEventStorePostgresTest
```

On Windows PowerShell, set the `RUN_PERSISTENCE_POSTGRES_TESTS` environment variable to `true` before running `./mvnw.cmd test -Dtest=JdbcUptimeEventStorePostgresTest`. Connection overrides are `UPTIME_TEST_DB_URL`, `UPTIME_DB_USER`, `UPTIME_DB_PASSWORD`; the default is local `uptime_test` with user/password `uptime`. Tests check UUID retry equality, child collision/parent rollback, committed progress rollback, start uniqueness, stale/idempotent stop snapshots, precise same-second session reads, nanosecond boundaries and both recovery cases. Use a dedicated migrated database with no concurrent service writers: recovery intentionally marks all open sessions interrupted. Cleanup deletes only the tests' randomly generated session IDs and their children. Without the opt-in environment variable PostgreSQL tests are skipped.

The full `UptimeServiceApplicationTests` suite uses the real Boot persistence/transaction proxies and `test` database profile, with scheduling disabled and mocked Clock/HealthProbe inputs (real ExecuteCheck, aggregation and TrackingService). Run it only against migrated `uptime_test`, with no concurrent service writers. It checks explicit lifecycle rows, retained STOPPING intent before flush, health-control independence, both bad-event types, inclusive exact-start points, exclusive exact-stop 422 errors, UNKNOWN/PENDING behavior, same-second sessions, lifecycle conflicts and whole-batch/child rollback. Fixtures use monotonic per-test clock blocks and random owned session IDs; cleanup drains in-memory tracking and deletes only owned child/parent/event/session rows in FK order. One legacy-row test deletes only its own checked-unoccupied timestamp. Production startup recovery still intentionally interrupts any open database sessions before the tests start.
