# CLAUDE.md — uptime-service

This file provides guidance to Claude Code (claude.ai/code) when working in the `uptime-service` module. Keep it up to date: if a change alters the module's behavior, architecture, configuration, or commands, update this file in the same change.

## Overview

A Spring Boot 4.1 / Java 21 service that is a pure health **provider**. It only answers calls and has no database and no scheduled jobs. `../uptime-monitor` calls its `GET /api/health` once per round of each deal (and every 2 s for its dashboard), relays each result to the `uptime_deal` program as the oracle, and stores the deals it settles. It builds on the host with an installed Maven 3.9 (`mvn`; `../scripts/setup-toolchain.sh` installs it) and has no Maven wrapper. The repo's devcontainer has no JDK.

## Commands

Run these from `uptime-service/`:

```bash
mvn spring-boot:run                                                  # start on :8080, no database needed
mvn test                                                             # all tests (a few seconds)
mvn test -Dtest=UptimeServiceApplicationTests                        # one class
mvn test -Dtest=UptimeServiceApplicationTests#apiHealthIsAlways200   # single test
mvn package                                                          # build jar
```

Endpoints: `GET /api/health`, `POST /api/application/{start,stop}`, `GET /api/application/state`, `/actuator/health`. Swagger UI is at `/swagger-ui.html` and OpenAPI at `/v3/api-docs`.

## Architecture

1. **`state/ApplicationStateService`** is an `AtomicBoolean` up/down switch, toggled by `POST /api/application/{stop,start}` (`web/ApplicationControlController`; `GET /api/application/state` reads it). Stopping does **not** stop the process. It only flips the switch.
2. **`state/ApplicationStateHealthIndicator`** exposes the switch as the `applicationState` component of `/actuator/health`. When it is DOWN, the endpoint returns 503 with `details.reason` = "stopped via API".
3. **`web/HealthController`** exposes the same switch as `GET /api/health` for `uptime-monitor`. It always answers 200 with `{"status":"UP"|"DOWN"}`, so the monitor reports a stop as a DOWN round.
4. **`web/ApiExceptionHandler`** maps `IllegalArgumentException` to a 400 `ProblemDetail`.

Lombok is available (`optional`, version managed by Spring Boot, wired as an explicit annotation processor in `maven-compiler-plugin`, and excluded from the boot jar). `lombok.config` marks generated code `@lombok.Generated`.

Configuration (`application.properties`) holds only the app name, health exposure with details, and springdoc settings.

## Testing notes

Tests need no external service.

- **Unit tests** (no Spring) cover `state/`.
- **`UptimeServiceApplicationTests`** is a full `@SpringBootTest` with MockMvc and the `test` profile. It checks startup and bean wiring (no `DataSource`), the actuator health transitions, `/api/health`, idempotent start and stop, 405 on GET `/api/application/stop`, `/api/application/state`, that the OpenAPI doc lists only the provider paths, and 404 for unknown paths. Tests share the singleton `ApplicationStateService`, and `@AfterEach` calls `state.start()` to reset it. Any new test that toggles the state must leave it UP.

Add tests for every new feature: unit tests for logic, and `UptimeServiceApplicationTests` for new endpoints or configuration.
