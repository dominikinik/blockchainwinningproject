# CLAUDE.md — uptime-monitor

This file provides guidance to Claude Code (claude.ai/code) when working in the `uptime-monitor` module. Keep it up to date: if a change alters the module's behavior, architecture, configuration, or commands, update this file in the same change.

## Overview

A Spring Boot 4.1 / Java 21 service that tracks the health of other services, publishes their downtime to Solana, and is the **oracle** of the `uptime_deal` program (`../uptime-deal`): it settles deals from the events of the service they pay for. It builds with an installed Maven 3.9 (`mvn`; `../scripts/setup-toolchain.sh` installs it) and has no Maven wrapper. It is the data-collecting half of the uptime split: `../uptime-service` is the **provider** (health endpoint plus start/stop), and this module **subscribes** to a provider's health endpoint, calls it every 2 s, records what it sees as events, and after each relevant event sends the aggregated downtime on chain and lets the deal oracle react. On startup it subscribes the **default service** (`monitor.default-service.*`, the provider at `http://localhost:8080/api/health`), so the dashboard history (`/api/uptime`) and deals registered without a `serviceId` have data. Events and deals are stored in PostgreSQL from the sibling `monitor-db` module.

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
  - `TrackingEvent` is a sealed interface. `TrackingStarted(healthUrl, checkInterval)` comes from subscribe. Every healthy check is persisted as `HealthCheckSucceeded`; `Downtime` records a 200 whose body says `DOWN`/`OUT_OF_SERVICE` or a 404; `InternalErrorHappened` records other failures. `TrackingFinished` comes from unsubscribe. Healthy observations are stored for history/UI and do not publish downtime memos.
  - `HealthCheckResult` classifies one response according to the rules above.
  - `ServiceTracking` is the **aggregate root**. It is rebuilt from its events (`rehydrate`) and is never stored. Commands (`start`, `recordCheck`, `finish`) enforce the invariants: you can't start an active service, and you can't check or finish an inactive one. Each command records pending events, and state changes only in `apply`. **Total downtime = number of `Downtime` events × the check interval in force**, summed over every tracking period. A finished service can be started again under the same UUID, and its earlier downtime is kept. `InternalErrorHappened` events are counted but are not downtime.
  - `DowntimeReport` is what gets published, `TrackingSummary` is a read model, and `TrackingException.{AlreadyActive,NotActive}` are invariant violations.
  - Ports: `TrackingEventStore` (append with an expected version, which gives optimistic concurrency), `HealthProbe`, and `DowntimePublisher`.
- **`domain/deal/`**: the deal oracle's model.
  - `UptimeDeal` tracks proposal acceptance and settlement transaction lifecycle for one service. The payer proposes and locks payment; the provider accepts and locks the guarantee. The chain sets the window start. The monitor reports each new health-check observation directly to Solana and stores tracked deal state in Postgres; it does not reconstruct round observations from historical event rows or decide the payout.
  - Every deal round must use the monitor sampling interval. A successful probe reports UP for the just-completed round; a failed probe reports DOWN. The program enforces one observation per round, maintains its own counters, and applies the agreed threshold. After expiry anyone can call `settle_deal`; the monitor can also submit it early when recorded DOWN rounds mathematically prove the threshold unreachable.
  - Ports: `UptimeDealRepository` and `DealChain` (read a deal account, send `settle_deal`, check a signature, find the closure of a vanished deal, fund the oracle).
- **`application/DealService`** (a `TrackingEventListener`) is the oracle. Its methods are synchronized, because the check threads, the scheduler and HTTP requests all change deals.
  - `register(address, serviceId?)` links the deal to the default service when `serviceId` is omitted. The service must be tracked. It reads the account, which must be owned by the program, name this oracle, and have a duration in `[1, monitor.deal.max-duration-seconds]`, then funds the oracle. A proposal is stored as `PROPOSED`.
  - `onTrackingEvent` sends the just-completed round's UP/DOWN report for each accepted deal linked to the service. It never reads Postgres history to derive an observation. Proposals ignore checks until accepted.
  - `settleDue()` runs every `monitor.deal.poll-interval-ms`. For each proposal it re-reads the account to pick up acceptance and the chain-set window start. For each accepted deal it requests settlement when the on-chain counters prove a breach or the window and grace have elapsed, then confirms the transaction. Anyone may call `settle_deal` after expiry; the contract computes the payout from its own counters. A vanished account is resolved from its recent closing event history.
- **`application/UptimeHistory`** is a per-second read model for `/api/uptime`. A second is down when the service wasn't tracked or it falls in `[t, t + interval)` of a `Downtime`. The range is `[from, to]` inclusive and defaults to the last `monitor.history.default-range-seconds`, capped at `max-range-seconds`.
- **`application/TrackingService`** holds the use cases: `subscribe`, `unsubscribe`, `check`, `checkActive`, `get`, `list`, and `events`. Each health result is persisted. Healthy observations go to listeners without publishing downtime memos; failures and tracking-finished events also publish the current downtime summary. The oracle handles each result directly and does not reload history to reconstruct rounds. `checkActive` checks every active service concurrently on virtual threads.
- **`infrastructure/`**: the adapters.
  - `persistence/JdbcUptimeDealRepository`: `JdbcClient` on the `uptime_deal` table, one row per deal, updated in place. A duplicate address raises `DealAlreadyRegisteredException`.
  - `solana/SolanaDealChain` implements `DealChain` on `SolanaRpc`, `OracleKey`, `SolanaTransaction` and `DealProgram`. `DealProgram` holds the program's binary layout, per-round observation and settlement instructions, and closing events.
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
  - `POST {address, serviceId?}` returns 201.
  - `GET` returns all deals, most recently proposed first. `GET /{address}` returns one deal, with `guaranteeLamports` and `acceptDeadline`; `startsAt`/`endsAt` are null while `PROPOSED`.
  - When `monitor.blockchain.enabled=false` the oracle doesn't exist and every deal endpoint returns 503.
- **`interfaces/web/UptimeController`** serves `GET /api/uptime?from&to&serviceId` as `[{time, down}]`. `serviceId` defaults to the default service; with neither, the response is 400.
- `ApiExceptionHandler` maps errors: `IllegalArgumentException` (including a bad UUID) → 400, `NoSuchElementException` → 404, `TrackingException` and `DealAlreadyRegisteredException` → 409, `SolanaRpcException` → 502.

Lombok is available (`optional`, version managed by Spring Boot, wired as an explicit annotation processor in `maven-compiler-plugin`, and excluded from the boot jar). `lombok.config` marks generated code `@lombok.Generated` so coverage tools skip it. Your IDE needs the Lombok plugin or annotation processing turned on.

Persistence: `spring.datasource.*` defaults to `jdbc:postgresql://localhost:5433/monitor` (user/password `monitor`). You can override it with `MONITOR_DB_URL`, `MONITOR_DB_USER` and `MONITOR_DB_PASSWORD`. If you change the schema, update `monitor-db/init/02-schema.sh`, `JdbcTrackingEventStore` or `JdbcUptimeDealRepository`, and `src/test/resources/schema.sql` together.

Configuration (`application.properties`, bound to the `MonitorProperties` record): `monitor.check-interval-ms` (2000), `monitor.probe-timeout-ms` (1500; keep it below the interval), and `monitor.blockchain.{enabled, rpc-url (SOLANA_RPC_URL), rpc-timeout-ms, oracle-keypair (MONITOR_ORACLE_KEYPAIR, default .oracle-keypair.json, gitignored; blank = new key per start), oracle-min-lamports, oracle-airdrop-lamports}`, `monitor.deal.{program-id, max-duration-seconds (3600), settle-grace-seconds (2), poll-interval-ms (500), max-settle-attempts (10), confirm-timeout-seconds (30)}`, `monitor.default-service.{id (MONITOR_DEFAULT_SERVICE_ID), health-url (MONITOR_DEFAULT_SERVICE_URL; blank = none)}`, and `monitor.history.{default-range-seconds (300), max-range-seconds (86400)}`. Time comes from the injected `Clock` bean.

## Testing notes

The tests need no database, network, chain or provider, and they never sleep. They run on in-memory H2 in PostgreSQL mode, using `src/test/resources/schema.sql`, a copy of the `monitor-db` tables.

- Unit tests per layer: `HealthCheckResultTest` and `ServiceTrackingTest` (domain); `TrackingServiceTest` (a fake probe, the in-memory store, a capturing publisher, and `support/MutableClock`, covering races, retries, and publish failures); `TrackingEventStoreContract`, an abstract set of tests that runs against both `JdbcTrackingEventStore` (H2 behind a Hikari pool, because H2 ties a CHECK constraint to the session that created it, so that connection must stay open) and the test fake `support/InMemoryTrackingEventStore`, which keeps the fake behaving like the real store. `JdbcTrackingEventStoreTest` adds checks for row numbering, concurrent writers racing for the same version, and an unknown event type in the table; `HttpHealthProbeTest` (`MockRestServiceServer`, plus a real refused port and a silent socket for the timeout); `SolanaMemoDowntimePublisherTest` (mocked `SolanaRpc`, which checks the signed memo bytes); `HealthCheckSchedulerTest`; and the copied Solana client tests.
- Deal oracle:
  - `UptimeDealTest` covers proposal and settlement lifecycle; the program's Rust tests cover round accounting and payout thresholds.
  - `DealServiceTest` runs the real `SolanaDealChain` over a mocked `SolanaRpc` with the in-memory store, the in-memory deal repository and `MutableClock`. It checks registration validation, early close, finish, window end, the signed `settle_deal` bytes, confirm/resend/fail, vanished deals and funding.
  - `UptimeDealRepositoryContract` runs against `JdbcUptimeDealRepository` and the fake `support/InMemoryUptimeDealRepository`.
  - Also: `UptimeHistoryTest`, `DefaultServiceSubscriberTest` and `DealProgramTest`. `support/DealFixtures` builds account data and event logs the way the program writes them.
- `UptimeMonitorApplicationTests` is a full `@SpringBootTest` with MockMvc. Each Spring context gets its own H2 database (`${random.uuid}` in the URL), and the test uses `@MockitoBean` for `HealthProbe` and `SolanaRpc`. The `test` profile disables the scheduler, so tests call `TrackingService.check`/`checkActive` directly. It also covers the deal endpoints, using the real wiring: a `Downtime` decides a registered deal, and `settleDue` sends it. It covers `/api/uptime` too. The test profile has no default service. `BlockchainDisabledTests` checks the logging-publisher wiring, that `/api/deals` returns 503, and that the default service is subscribed at startup, with the schedulers on.

Add tests for every new feature in the matching layer.
