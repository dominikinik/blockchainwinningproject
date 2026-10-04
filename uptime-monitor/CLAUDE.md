# CLAUDE.md — uptime-monitor

This file provides guidance to Claude Code (claude.ai/code) when working in the `uptime-monitor` module. Keep it up to date: if a change alters the module's behavior, architecture, configuration, or commands, update this file in the same change.

## Overview

A Spring Boot 4.1 / Java 21 service that tracks the health of other services, publishes their downtime to Solana, and is the **oracle** of the `uptime_deal` program (`../uptime-deal`): it settles deals from the events of the service they pay for. It builds with an installed Maven 3.9 (`mvn`; `../scripts/setup-toolchain.sh` installs it) and has no Maven wrapper. It is the data-collecting half of the uptime split: `../uptime-service` is the **provider** (health endpoint plus start/stop), and this module **subscribes** to a provider's health endpoint, calls it every 2 s, records what it sees as events, and after each relevant event sends the aggregated downtime on chain and lets the deal oracle react. On startup it subscribes the **default service** (`monitor.default-service.*`, the provider at `http://localhost:8080/api/health`), so the dashboard history (`/api/uptime`) has data. Deals are tracked on their own: each deal gets its own service, which is subscribed automatically when the deal is accepted and unsubscribed when the deal is settled, cancelled or failed. Its health URL defaults to the default service's. Events and deals are stored in PostgreSQL from the sibling `monitor-db` module.

## Commands

Running the service needs its database (see `../monitor-db/`). Tests don't:

```bash
docker compose -f ../monitor-db/docker-compose.yml up -d --wait   # PostgreSQL on :5433
```

Run these from `uptime-monitor/`:

```bash
mvn spring-boot:run                        # start on :8082 (UPTIME_MONITOR_PORT)
MONITOR_BLOCKCHAIN_ENABLED=false mvn spring-boot:run      # log reports instead of sending them
mvn test                                   # all tests (~8 s, no database, network or chain needed)
mvn test -Dtest=ServiceTrackingTest        # one class
mvn test -Dtest=TrackingServiceTest#unsubscribeRecordsTrackingFinishedAndPublishesTheFinalTotal      # single test
mvn package                                # build jar
```

Try it against the provider (`uptime-service` on :8080):

```bash
curl -XPOST localhost:8082/api/subscriptions -H 'Content-Type: application/json' \
  -d '{"healthUrl":"http://localhost:8080/api/health"}'          # -> {"serviceId": "<uuid>", ...}
curl -XPOST localhost:8080/api/application/stop                  # Downtime every 2 s from now on
curl localhost:8082/api/subscriptions/<uuid>/events
curl -XDELETE localhost:8082/api/subscriptions/<uuid>            # TrackingFinished
```

Swagger UI is at `/swagger-ui.html`, OpenAPI at `/v3/api-docs`, and the monitor's own health at `/actuator/health`.

## Architecture (DDD, event-sourced)

Packages under `com.example.monitor`. Dependencies point inward: `interfaces` and `infrastructure` → `application` → `domain`. The domain and application layers have no Spring imports. `infrastructure/config/MonitorConfig` wires them.

- **`domain/`**: the model and its ports.
  - `ServiceId`: the service UUID that all of a service's events are stored under.
  - `TrackingEvent` is a sealed interface with four events. `TrackingStarted(healthUrl, checkInterval)` comes from subscribe and is always first. `Downtime(httpStatus, reason)` is recorded for a 200 whose body `status` is `DOWN`/`OUT_OF_SERVICE`, and for a 404. `InternalErrorHappened(httpStatus?, reason)` covers any other status, a timeout, or no connection (`httpStatus` is then null). `TrackingFinished` comes from unsubscribe. Healthy checks record nothing.
  - `HealthCheckResult` classifies one response according to the rules above.
  - `ServiceTracking` is the **aggregate root**. It is rebuilt from its events (`rehydrate`) and is never stored. Commands (`start`, `recordCheck`, `finish`) enforce the invariants: you can't start an active service, and you can't check or finish an inactive one. Each command records pending events, and state changes only in `apply`. **Total downtime = number of `Downtime` events × the check interval in force**, summed over every tracking period. A finished service can be started again under the same UUID, and its earlier downtime is kept. `InternalErrorHappened` events are counted but are not downtime.
  - `DowntimeReport` is what gets published, `TrackingSummary` is a read model, and `TrackingException.{AlreadyActive,NotActive}` are invariant violations.
  - Ports: `TrackingEventStore` (append with an expected version, which gives optimistic concurrency), `HealthProbe`, and `DowntimePublisher`.
- **`domain/deal/`**: the deal oracle's model.
  - `UptimeDeal` is the deal **aggregate root**: immutable and state-based, stored in `uptime_deal`. It has its own `serviceId` (`UptimeDeal.serviceIdFor(address)`, a name-based UUID of the address) and the `healthUrl` that service checks. No other deal shares that service. The program is two-sided: the payer proposes and locks the payment, and the recipient (provider) accepts and locks a guarantee (`guaranteeLamports`). The deal is `PROPOSED`, with no window, until `accepted(startsAt)`; the window `[startsAt, startsAt + durationSeconds)` starts at the chain time of the acceptance. It is then `ACTIVE` until a settlement is **decided** (`decide(Verdict)` fixes `upSeconds`/`totalSeconds` once). Then it is sent (`sent`), confirmed (`settled`), found closed on chain (`settledBy`/`cancelled`), or given up (`failedAttempt`, then `failed`). `isOpen()` means not decided yet; `isSettling()` means decided but not finished; `isFinished()` means `SETTLED`, `CANCELLED` or `FAILED`.
  - `DealMeasurement` measures a window from the service's events as of `now`, in whole seconds:
    - `covered` is the elapsed window seconds while the service was tracked; untracked time is never up. The one exception is the start grace: when the service's first `TrackingStarted` comes at most `startGrace` after the window start (the oracle noticing the acceptance), the time before it counts as tracked too.
    - Each `Downtime` that has happened marks `[t, t + interval)` as down, cut at the window end, with overlaps counted once. That splits into `down` (already elapsed) and `downAhead` (still to come).
    - From those: `upSoFar = covered − down`, `remaining`, and `bestCaseUp = upSoFar + remaining − downAhead`.
  - `SettlementPolicy` decides when to settle:
    - On a failure (`Downtime`, `InternalErrorHappened`), settle **now** with `(bestCaseUp, total)` when `bestCaseUp*100 ≤ total*99`. 99% is then unreachable, so the deal closes and the payer is refunded.
    - On `TrackingFinished`, settle **now** with `(upSoFar, total)`: the rest of the window isn't watched, so it counts as down.
    - At the window end, settle with `(upSoFar, total)`.
    - `aboveThreshold` mirrors the program's `up*100 > total*99`.
  - `Verdict(up, total)` is validated like the program does (`total > 0`, `up ≤ total`).
  - Ports: `UptimeDealRepository` and `DealChain` (read a deal account, send `settle_deal`, check a signature, find the closure of a vanished deal, fund the oracle).
- **`application/DealService`** (a `TrackingEventListener`) is the oracle. Its methods are synchronized, because the check threads, the scheduler and HTTP requests all change deals. It starts and stops each deal's tracking through the `application/ServiceMonitor` port, which `TrackingService` implements (`start` subscribes unless the service is active, `stop` unsubscribes if it is). Nobody subscribes a deal's service by hand.
  - `register(address, healthUrl?)` uses the default service's health URL when `healthUrl` is omitted, and the URL must be absolute http(s). It reads the account, which must be owned by the program, name this oracle, and have a duration in `[1, monitor.deal.max-duration-seconds]`, then funds the oracle. A proposal is stored as `PROPOSED` and is not tracked. A deal that is already accepted is stored `ACTIVE`, and its service is subscribed at once.
  - `onTrackingEvent` applies the policy to the service's open deals (accepted, not yet decided) and only **decides**. Proposals ignore events.
  - `settleDue()` runs every `monitor.deal.poll-interval-ms`. For each proposal it re-reads the account: an acceptance makes the deal `ACTIVE` with the window from the chain and **subscribes the deal's service**; a vanished account becomes `CANCELLED` (withdrawn or rejected, read from its history); a read failure leaves it waiting. For active deals it decides those whose window plus `settle-grace-seconds` is over, sends decided settlements, and confirms sent ones. It resends after `confirm-timeout-seconds`, fails after `max-settle-attempts`, and explains a vanished account from its last 10 transactions as `SETTLED` or `CANCELLED` (otherwise `FAILED`). One deal's failure never blocks the others.
  - An open deal whose service isn't tracked (a failed subscribe, or a crash in between) is subscribed again on the next tick.
  - Once a deal becomes `SETTLED`, `CANCELLED` or `FAILED`, its service is unsubscribed. The resulting `TrackingFinished` finds no open deal, so it changes nothing.
  - Measurements use `monitor.deal.start-grace-seconds`, so the delay before the acceptance is noticed doesn't count as downtime. Tracking that starts later than that leaves the gap untracked, which counts as down.
  - Unsubscribing a deal's service by hand (`DELETE /api/subscriptions/{serviceId}`) still settles the deal at once with the uptime so far.
- **`application/UptimeHistory`** is a per-second read model for `/api/uptime`. A second is down when the service wasn't tracked or it falls in `[t, t + interval)` of a `Downtime`. The range is `[from, to]` inclusive and defaults to the last `monitor.history.default-range-seconds`, capped at `max-range-seconds`.
- **`application/TrackingService`** holds the use cases: `subscribe`, `unsubscribe`, `check`, `checkActive`, `get`, `list`, and `events`. Each command loads the aggregate, runs, and appends with the version it loaded; on a `ConcurrencyException` it retries up to 5 times. The health call happens **outside** that loop, so a check that races an unsubscribe is dropped. After appending, every event whose `triggersDowntimeReport()` is true (`Downtime`, `InternalErrorHappened`, `TrackingFinished`) makes the service reload **all** its events up to that one, rehydrate the aggregate, and send `aggregate.report(trigger)` to the publisher. It then passes the event to every `TrackingEventListener`, which here is `DealService`. A publish or listener failure is logged and does not undo the event. There is no retry, but each report carries the cumulative total, so the next one supersedes a missed one. `checkActive` checks every active service concurrently on virtual threads.
- **`infrastructure/`**: the adapters.
  - `persistence/JdbcUptimeDealRepository`: `JdbcClient` on the `uptime_deal` table, one row per deal, updated in place. A duplicate address raises `DealAlreadyRegisteredException`.
  - `solana/SolanaDealChain` implements `DealChain` on `SolanaRpc`, `OracleKey`, `SolanaTransaction` and `DealProgram`. `DealProgram` holds the program's binary layout: the 137-byte `Deal` account, `settle_deal`, and the `DealSettled`/`DealCancelled` events.
  - `scheduling/DealSettlementScheduler` calls `settleDue()`. `scheduling/DefaultServiceSubscriber` subscribes the default service on `ApplicationReadyEvent` unless it is already active.
  - `persistence/JdbcTrackingEventStore`: `JdbcClient` on the `tracking_event` table owned by `../monitor-db`, with one row per event and versions 1, 2, … per service. `append` runs in one transaction: it compares `MAX(version)` with the expected version, then inserts. The `(service_id, version)` primary key catches a writer that raced past that check, and the resulting `DuplicateKeyException` becomes `ConcurrencyException`. `serviceIds` lists services by the `id` of their first event. Because events survive a restart, services that were active resume being checked as soon as the monitor starts again.
  - `probe/HttpHealthProbe`: a `RestClient` GET with a connect/read timeout of `monitor.probe-timeout-ms`. It reads the JSON `status` only on a 200, and treats a non-JSON 200 as healthy.
  - `solana/SolanaMemoDowntimePublisher`: writes each report on chain as an **SPL Memo** transaction (`MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr`, present on every cluster including `solana-test-validator`) signed by the oracle key. The memo is compact JSON: `{"app":"uptime-monitor","serviceId","trigger","totalDowntimeMs","downtimeChecks","internalErrors","active","at"}`. Before each send it tops up from the faucet when the balance is below `oracle-min-lamports`. A fresh key's first report can fail before its airdrop lands. `LoggingDowntimePublisher` replaces it when `monitor.blockchain.enabled=false`. The Solana client (`Base58`, `Ed25519`, `OracleKey`, `SolanaTransaction`, `SolanaRpc`/`HttpSolanaRpc`) is a copy of `uptime-service`'s hand-rolled one; the modules share no build.
  - `scheduling/HealthCheckScheduler`: `@Scheduled(fixedRate = monitor.check-interval-ms)` → `checkActive()`. It is off when `monitor.scheduler.enabled=false`.
- **`interfaces/web/SubscriptionController`** under `/api/subscriptions`:
  - `POST {healthUrl, serviceId?}`: 201 with a summary. `healthUrl` must be an absolute http(s) URL (otherwise 400). `serviceId` is optional; send it to resume a finished UUID. A service that is already tracked gives 409.
  - `DELETE /{id}`: finishes tracking and returns the summary. An unknown id gives 404; a service that is no longer tracked gives 409.
  - `GET` lists all services. `GET /{id}` returns `active`, `startedAt`, `finishedAt`, `totalDowntimeMs`, `downtimeChecks`, `internalErrors` and `eventCount`. `GET /{id}/events` returns `{type, serviceId, occurredAt, httpStatus, detail}`, oldest first.
- **`interfaces/web/DealController`** under `/api/deals`. The JSON shape is the one the frontend reads, plus `serviceId`.
  - `GET /config` returns `{programId, oracle, rpcUrl}`.
  - `POST {address, healthUrl?}` returns 201. `healthUrl` defaults to the default service's endpoint. The response's `serviceId` is the deal's own service, whose events are at `/api/subscriptions/{serviceId}/events` once it is accepted.
  - `GET` returns all deals, most recently proposed first. `GET /{address}` returns one deal, with `guaranteeLamports` and `acceptDeadline`; `startsAt`/`endsAt` are null while `PROPOSED`.
  - When `monitor.blockchain.enabled=false` the oracle doesn't exist and every deal endpoint returns 503.
- **`interfaces/web/UptimeController`** serves `GET /api/uptime?from&to&serviceId` as `[{time, down}]`. `serviceId` defaults to the default service; with neither, the response is 400.
- `ApiExceptionHandler` maps errors: `IllegalArgumentException` (including a bad UUID) → 400, `NoSuchElementException` → 404, `TrackingException` and `DealAlreadyRegisteredException` → 409, `SolanaRpcException` → 502.

Lombok is available (`optional`, version managed by Spring Boot, wired as an explicit annotation processor in `maven-compiler-plugin`, and excluded from the boot jar). `lombok.config` marks generated code `@lombok.Generated` so coverage tools skip it. Your IDE needs the Lombok plugin or annotation processing turned on.

Persistence: `spring.datasource.*` defaults to `jdbc:postgresql://localhost:5433/monitor` (user/password `monitor`). You can override it with `MONITOR_DB_URL`, `MONITOR_DB_USER` and `MONITOR_DB_PASSWORD`. If you change the schema, update `monitor-db/init/02-schema.sh`, `JdbcTrackingEventStore` or `JdbcUptimeDealRepository`, and `src/test/resources/schema.sql` together.

Configuration (`application.properties`, bound to the `MonitorProperties` record): `monitor.check-interval-ms` (2000), `monitor.probe-timeout-ms` (1500; keep it below the interval), and `monitor.blockchain.{enabled, rpc-url (SOLANA_RPC_URL), rpc-timeout-ms, oracle-keypair (MONITOR_ORACLE_KEYPAIR, default .oracle-keypair.json, gitignored; blank = new key per start), oracle-min-lamports, oracle-airdrop-lamports}`, `monitor.deal.{program-id, max-duration-seconds (3600), settle-grace-seconds (2), start-grace-seconds (5), poll-interval-ms (500), max-settle-attempts (10), confirm-timeout-seconds (30)}`, `monitor.default-service.{id (MONITOR_DEFAULT_SERVICE_ID), health-url (MONITOR_DEFAULT_SERVICE_URL; also the deals' default health URL; blank = none)}`, and `monitor.history.{default-range-seconds (300), max-range-seconds (86400)}`. Time comes from the injected `Clock` bean.

## Testing notes

The tests need no database, network, chain or provider, and they never sleep. They run on in-memory H2 in PostgreSQL mode, using `src/test/resources/schema.sql`, a copy of the `monitor-db` tables.

- Unit tests per layer: `HealthCheckResultTest` and `ServiceTrackingTest` (domain); `TrackingServiceTest` (a fake probe, the in-memory store, a capturing publisher, and `support/MutableClock`, covering races, retries, and publish failures); `TrackingEventStoreContract`, an abstract set of tests that runs against both `JdbcTrackingEventStore` (H2 behind a Hikari pool, because H2 ties a CHECK constraint to the session that created it, so that connection must stay open) and the test fake `support/InMemoryTrackingEventStore`, which keeps the fake behaving like the real store. `JdbcTrackingEventStoreTest` adds checks for row numbering, concurrent writers racing for the same version, and an unknown event type in the table; `HttpHealthProbeTest` (`MockRestServiceServer`, plus a real refused port and a silent socket for the timeout); `SolanaMemoDowntimePublisherTest` (mocked `SolanaRpc`, which checks the signed memo bytes); `HealthCheckSchedulerTest`; and the copied Solana client tests.
- Deal oracle:
  - `DealMeasurementTest`, `SettlementPolicyTest` (including the exact 99% boundary) and `UptimeDealTest` cover the domain.
  - `DealServiceTest` runs the real `SolanaDealChain` over a mocked `SolanaRpc`, with the in-memory store, the in-memory deal repository, `MutableClock`, and a real `TrackingService` as the `ServiceMonitor`. It checks:
    - that tracking starts at acceptance (not for proposals) and stops on `SETTLED`/`CANCELLED`/`FAILED`,
    - the start grace,
    - re-subscribing after a failed start,
    - registration validation, early close, finish, window end, the signed `settle_deal` bytes, confirm/resend/fail, vanished deals and funding.
  - `UptimeDealRepositoryContract` runs against `JdbcUptimeDealRepository` and the fake `support/InMemoryUptimeDealRepository`.
  - Also: `UptimeHistoryTest`, `DefaultServiceSubscriberTest` and `DealProgramTest`. `support/DealFixtures` builds account data and event logs the way the program writes them.
- `UptimeMonitorApplicationTests` is a full `@SpringBootTest` with MockMvc. Each Spring context gets its own H2 database (`${random.uuid}` in the URL), and the test uses `@MockitoBean` for `HealthProbe` and `SolanaRpc`. The `test` profile disables the scheduler, so tests call `TrackingService.check`/`checkActive` directly. It also covers the deal endpoints, using the real wiring: a `Downtime` decides a registered deal, and `settleDue` sends it. It covers `/api/uptime` too. The test profile has no default service. `BlockchainDisabledTests` checks the logging-publisher wiring, that `/api/deals` returns 503, and that the default service is subscribed at startup, with the schedulers on.

Add tests for every new feature in the matching layer.
