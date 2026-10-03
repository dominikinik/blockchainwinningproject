# CLAUDE.md — uptime-service

This file provides guidance to Claude Code (claude.ai/code) when working in the `uptime-service` module. Keep it up to date: if a change alters the module's behavior, architecture, configuration, or commands, update this file in the same change.

## Overview

A Spring Boot 4.1 / Java 21 service that monitors its own health and records per-second uptime history. It builds on the host machine; the repo's devcontainer has no JDK.

## Commands

Running the service needs the database (see `../uptime-db/`). Tests don't:

```bash
docker compose -f ../uptime-db/docker-compose.yml up -d --wait   # PostgreSQL on :5432
```

Run these from `uptime-service/`:

```bash
./mvnw spring-boot:run                     # start on :8080
./mvnw test                                # all tests (~5 s, no database needed)
./mvnw test -Dtest=UptimeQueryServiceTest  # one class
./mvnw test -Dtest=UptimeServiceApplicationTests#listReturnsEverySecondWithGapsAsDown   # single test
./mvnw package                             # build jar
```

Swagger UI is served at `/swagger-ui.html`, OpenAPI at `/v3/api-docs`, and health at `/actuator/health`.

## Architecture

There is one data flow. A logical switch drives health, health is sampled, samples are aggregated, and the API reads the aggregates back:

1. **`state/ApplicationStateService`** is an `AtomicBoolean` up/down switch, toggled by `POST /api/application/{stop,start}`. Stopping does **not** stop the process. It only flips the switch.
2. **`state/ApplicationStateHealthIndicator`** exposes the switch as the `applicationState` component of `/actuator/health`. When it is DOWN, the endpoint returns 503.
3. **`monitor/UptimeSampler`** has two `@Scheduled` jobs. `sample()` runs every `uptime.sample-interval-ms` (10 ms) and reads the health indicator into in-memory per-epoch-second buckets. `flush()` runs every `uptime.flush-interval-ms` (1 s) and persists every *completed* second; the current second stays in memory while it is still filling. A second counts as up only if **every** sample in it was UP. The scheduler pool size is 2 (`spring.task.scheduling.pool.size`) so that sampling and flushing don't block each other.
4. **`uptime/UptimeRecord`** stores one row per second, keyed by the second's start `Instant` (`ts` column, UTC, truncated).
5. **`uptime/UptimeQueryService`** backs `GET /api/uptime` (an inclusive `[from, to]` range, one point per second) and `GET /api/uptime/at`. **Any second with no record is reported as down**, so time when the process was off counts as downtime. The range defaults to the last `uptime.default-range-seconds` seconds and is capped at `uptime.max-range-seconds`. An invalid range throws `IllegalArgumentException`, which `web/ApiExceptionHandler` maps to a 400 `ProblemDetail`.

Configuration is bound through the `UptimeProperties` record (`uptime.*` in `application.properties`). Time comes from an injected `Clock` bean (`ClockConfig`). Use that bean instead of calling `Instant.now()` in production code.

Persistence uses PostgreSQL from the sibling **`uptime-db`** module (Docker Compose, `postgres:17-alpine`, user/password `uptime`). That module owns the schema (`uptime-db/init/`, which runs only when `uptime-db/data/` is empty) and the data files (`uptime-db/data/`, gitignored). The service runs with `ddl-auto=validate`, so if you change the schema, update both `init/02-schema.sh` and `UptimeRecord`, then recreate the data directory. History survives restarts. You can override the connection with `UPTIME_DB_URL`, `UPTIME_DB_USER`, and `UPTIME_DB_PASSWORD`. Tests use the `test` profile, which runs on in-memory H2 instead (see Testing notes).

Handling of rejected DB transactions is an open TODO on both paths: writes in `UptimeSampler.flush()` (drained buckets are lost on failure) and reads in `UptimeQueryService` (currently a 500).

## Monitoring targets

> **Status:** contract only, frozen in Phase 0. Task T3 implements it. Until then, none of these endpoints exist.

The service also checks customer SLA endpoints and serves a per-check timeline with latency. This is the informational, off-chain path. It never decides payouts (the `sla` program does). A target is keyed by the SLA's on-chain account address.

```
POST   /api/monitoring/targets
       body {slaAccount, endpoint, checkIntervalSeconds, timeoutMs}
       201 Target                     created
       200 Target                     a target for this slaAccount already exists; it is returned
                                      unchanged and the rest of the body is ignored (idempotent)
GET    /api/monitoring/targets?slaAccount={base58}   200 Target | 404
GET    /api/monitoring/targets/{targetId}            200 Target | 404
DELETE /api/monitoring/targets/{targetId}            204 | 404
GET    /api/monitoring/targets/{targetId}/uptime?from=&to=
       200 [{time, down, latencyMs, statusCode}]    one entry per check, oldest first | 404
```

- **`Target`:** `{targetId, slaAccount, endpoint, checkIntervalSeconds, timeoutMs, createdAt}`. `targetId` is a server-generated UUID string, and `createdAt` is an ISO-8601 UTC instant.
- **Validation (400):**
  - `slaAccount` must be a base58 string that decodes to 32 bytes.
  - `endpoint` must be an absolute `https://` URL of at most 200 characters.
  - `checkIntervalSeconds` must be ≥ 10.
  - `timeoutMs` must be in `100..30000` and less than `checkIntervalSeconds · 1000`.

  These match `create_sla` in `sla-program/SPEC.md`.
- **Check:** a GET to `endpoint` every `checkIntervalSeconds`. It is **up** when it returns a 2xx within `timeoutMs`.
- **Check entries:**
  - `time` is the ISO-8601 UTC instant when the check started.
  - `down` is a boolean.
  - `latencyMs` is the response time, or `null` on timeout or connection error.
  - `statusCode` is the HTTP status, or `null` when there was no response.
- **Uptime range:** `from` and `to` are optional ISO-8601 instants, inclusive. The defaults and range cap follow `GET /api/uptime`: `uptime.default-range-seconds` and `uptime.max-range-seconds`. An invalid or over-cap range is a 400. Unlike `/api/uptime`, gaps aren't filled. Only checks that ran are returned.
- **Errors:** every 400 is a `ProblemDetail` from `web/ApiExceptionHandler`, the same as the existing endpoints. An unknown `targetId` or `slaAccount` returns 404.

## Testing notes

Tests are self-contained and run on in-memory H2 in PostgreSQL mode (`src/test/resources/application-test.properties`), so they need no `uptime-db` container. `src/test/resources/schema.sql` copies the table from `uptime-db/init/02-schema.sh`, and `ddl-auto=validate` still checks `UptimeRecord` against it. If you change the schema, update all three.

- **Unit tests** (no Spring) cover `state/`, `monitor/UptimeSampler` and `uptime/UptimeQueryService`, using a Mockito-mocked repository and `support/MutableClock`. Move the clock by hand instead of using `Thread.sleep`.
- **`UptimeServiceApplicationTests`** is a full `@SpringBootTest` with MockMvc. It checks startup, bean wiring and the scheduled jobs, and calls every HTTP endpoint, including the error cases. The real schedulers run during it, so the records it writes use dates in 2000 to avoid collisions. Tests share the singleton `ApplicationStateService`, and `@AfterEach` calls `state.start()` to reset it. Any new test that toggles the state must leave it UP.

Add tests for every new feature in the matching layer: unit tests for logic, and `UptimeServiceApplicationTests` for new endpoints or configuration.
