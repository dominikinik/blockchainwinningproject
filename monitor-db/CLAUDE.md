# CLAUDE.md — monitor-db

This file provides guidance to Claude Code (claude.ai/code) when working in the `monitor-db` module. Keep it up to date: if a change alters the schema, databases, credentials, ports, or commands, update this file in the same change.

## Overview

The local PostgreSQL 17 database for `uptime-monitor`: its event store and its uptime deals. It runs with Docker Compose (`postgres:17-alpine`, container `monitor-db`). This module owns the schema and the data files. The monitor only reads and writes rows and never creates tables. It is the project's only database; `uptime-service` needs none.

## Commands

Run these from the repo root (or drop the `-f` path when running from `monitor-db/`):

```bash
docker compose -f monitor-db/docker-compose.yml up -d --wait   # start on :5433; waits for the healthcheck
docker compose -f monitor-db/docker-compose.yml down           # stop (data is kept)
docker exec -it monitor-db psql -U monitor -d monitor          # SQL shell
```

```bash
sh monitor-db/test/run-tests.sh                               # module tests (~3 s, needs Docker)
```

To re-initialise the schema, run `down`, delete `monitor-db/data/`, then start again.

## Layout

- `docker-compose.yml`: host port **5433**; user, password and database all `monitor`; `restart: unless-stopped`.
- `init/` is mounted read-only as `/docker-entrypoint-initdb.d`. Postgres runs these scripts in name order, and **only when `data/` is empty**, so editing them does not change an existing database.
  - `01-databases.sql` creates `monitor_test`.
  - `02-schema.sh` creates `tracking_event` and `uptime_deal` in both `monitor` and `monitor_test`. It uses `CREATE TABLE IF NOT EXISTS`, so you can re-run it on an existing database to add new tables: `docker exec -e POSTGRES_USER=monitor monitor-db sh /docker-entrypoint-initdb.d/02-schema.sh` (`frontend/e2e/start-backend.sh` does this).
- `data/` holds the Postgres data files. It is gitignored.

## Schema

`tracking_event` is append-only and has one row per event:
- `id` is a `BIGINT GENERATED ALWAYS AS IDENTITY`. It gives the global insertion order, which is used to list services in the order they were first tracked.
- `service_id UUID` and `version BIGINT` (≥ 1) together form the **primary key**. That key is the optimistic-concurrency guard: two writers appending the same next version can't both succeed.
- `type` must be one of `TrackingStarted`, `Downtime`, `InternalErrorHappened`, `TrackingFinished` (enforced by a CHECK).
- `occurred_at` is `TIMESTAMP(6) WITH TIME ZONE`.
- The payload columns are NULL when an event type doesn't use them:
  - `health_url` and `check_interval_ms` (TrackingStarted)
  - `http_status` (Downtime, and InternalErrorHappened when the service answered)
  - `reason` (Downtime, InternalErrorHappened)

`uptime_deal` has one row per deal that the monitor settles as the oracle, updated in place:
- `address VARCHAR(44)` is the **primary key**. `service_id UUID` is the deal's own tracked service, derived from the address. `health_url TEXT NOT NULL` is the endpoint that service checks; the monitor tracks it from the acceptance until the deal is finished.
- `02-schema.sh` also adds `health_url` to databases created before the column existed. Existing rows get the provider's URL (`http://localhost:8080/api/health`), which is what earlier deals were measured on.
- `payer`, `recipient`, `amount_lamports` (≥ 0), `guarantee_lamports` (≥ 0), `duration_seconds` (≥ 1) and `accept_deadline` come from the chain. `starts_at` is the chain time of the recipient's acceptance; it is NULL while the deal is a proposal.
- `status` must be one of `PROPOSED`, `ACTIVE`, `SETTLED`, `FAILED`, `CANCELLED` (enforced by a CHECK).
- The settlement fields are NULL until they're set: `up_seconds`, `total_seconds`, `paid_to_recipient`, `signature`, `sent_at` and `error`. `attempts` defaults to 0. `registered_at` is required.

The schema must stay in step with `JdbcTrackingEventStore`, `JdbcUptimeDealRepository` and with `uptime-monitor/src/test/resources/schema.sql`, the H2 copy its tests use. If you change one, change all three, then recreate `data/`.

## Tests

`test/run-tests.sh` first checks the files without a database: the compose config, the syntax of the init scripts, and their run order. It then starts a throwaway `postgres:17-alpine` container with tmpfs storage and no published port, runs `init/` in it as compose would, and checks:
- both databases exist,
- the exact columns, types, nullability and primary keys of `tracking_event` and `uptime_deal`,
- that times are stored as absolute instants and `id` follows insertion order,
- that a duplicate version, an unknown type, a version below 1, NULLs in the required columns, and a hand-set `id` are all rejected,
- that a proposal row without a window start is accepted with `attempts` defaulting to 0, and that duplicate addresses, unknown statuses, zero durations, negative amounts or guarantees, and deals without a service or accept deadline are rejected.

It never touches the real `monitor-db` container or `data/`. When you change the schema or add an init script, update the expected values in this script in the same change.
