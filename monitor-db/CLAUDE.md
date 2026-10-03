# CLAUDE.md — monitor-db

This file provides guidance to Claude Code (claude.ai/code) when working in the `monitor-db` module. Keep it up to date: if a change alters the schema, databases, credentials, ports, or commands, update this file in the same change.

## Overview

The local PostgreSQL 17 database for `uptime-monitor`: its event store. It runs with Docker Compose (`postgres:17-alpine`, container `monitor-db`). This module owns the schema and the data files. The monitor only reads and writes rows and never creates tables. It is separate from `uptime-db`, which belongs to `uptime-service`, so that each service owns its own data.

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

- `docker-compose.yml`: host port **5433** (so it runs next to `uptime-db` on 5432); user, password and database all `monitor`; `restart: unless-stopped`.
- `init/` is mounted read-only as `/docker-entrypoint-initdb.d`. Postgres runs these scripts in name order, and **only when `data/` is empty**, so editing them does not change an existing database.
  - `01-databases.sql` creates `monitor_test`.
  - `02-schema.sh` creates `tracking_event` in both `monitor` and `monitor_test`.
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

The schema must stay in step with `JdbcTrackingEventStore` and with `uptime-monitor/src/test/resources/schema.sql`, the H2 copy its tests use. If you change one, change all three, then recreate `data/`.

## Tests

`test/run-tests.sh` first checks the files without a database: the compose config, the syntax of the init scripts, and their run order. It then starts a throwaway `postgres:17-alpine` container with tmpfs storage and no published port, runs `init/` in it as compose would, and checks:
- both databases exist,
- the exact columns, types, nullability and composite primary key of `tracking_event`,
- that times are stored as absolute instants and `id` follows insertion order,
- that a duplicate version, an unknown type, a version below 1, NULLs in the required columns, and a hand-set `id` are all rejected.

It never touches the real `monitor-db` container or `data/`. When you change the schema or add an init script, update the expected values in this script in the same change.
