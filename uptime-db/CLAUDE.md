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

```bash
sh uptime-db/test/run-tests.sh                               # module tests (~3 s, needs Docker)
```

To re-initialise the schema, run `down`, delete `uptime-db/data/`, then start again.

## Layout

- `docker-compose.yml` defines the database: port 5432, user, password and database all `uptime`, and `restart: unless-stopped`.
- `init/` is mounted read-only as `/docker-entrypoint-initdb.d`. Postgres runs these scripts in name order, and **only when `data/` is empty**, so editing them does not change an existing database.
  - `01-databases.sql` creates the `uptime_test` database, which the service's integration tests use.
  - `02-schema.sh` creates the `uptime_record` table in both `uptime` and `uptime_test`.
- `data/` holds the Postgres data files. It is gitignored. Uptime history lives here and survives container restarts.

## Schema

`uptime_record` has one row per second: `ts TIMESTAMP(6) WITH TIME ZONE PRIMARY KEY` (UTC, truncated to the second), `up BOOLEAN`, `samples INTEGER`, and `up_samples INTEGER`. A second with no row is treated as down by the service.

The schema must stay in step with the `UptimeRecord` entity in `uptime-service`. If you change one, change the other, then recreate `data/`; otherwise schema validation fails and the service won't start.

## Tests

`test/run-tests.sh` first checks the files without a database: the compose config, the syntax of the init scripts, and their run order. It then starts a throwaway `postgres:17-alpine` container with tmpfs storage and no published port, runs `init/` in it as compose would, and checks the result:
- both databases exist,
- the exact columns, types, nullability and primary key of `uptime_record`,
- time-zone handling,
- that duplicate seconds and NULLs are rejected.

It never touches the real `uptime-db` container or `data/`. When you change the schema or add an init script, update the expected values in this script in the same change.
