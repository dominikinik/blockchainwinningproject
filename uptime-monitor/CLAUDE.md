# CLAUDE.md — uptime-monitor

This file provides guidance to Claude Code (claude.ai/code) when working in the `uptime-monitor` module. Keep it up to date: if a change alters the module's behavior, architecture, configuration, or commands, update this file in the same change.

## Overview

A Spring Boot 4.1 / Java 21 service that tracks the health of other services and publishes their downtime to Solana. It is the data-collecting half of the uptime split: `../uptime-service` is the **provider** (health endpoint plus start/stop), and this module **subscribes** to a provider's health endpoint, calls it every 2 s, records what it sees as events, and after each relevant event sends the aggregated downtime on chain. It needs no database: events are kept in memory.

## Commands

Run these from `uptime-monitor/`:

```bash
./mvnw spring-boot:run                     # start on :8082 (UPTIME_MONITOR_PORT)
MONITOR_BLOCKCHAIN_ENABLED=false ./mvnw spring-boot:run   # log reports instead of sending them
./mvnw test                                # all tests (~7 s, no network or chain needed)
./mvnw test -Dtest=ServiceTrackingTest     # one class
./mvnw test -Dtest=TrackingServiceTest#unsubscribeRecordsTrackingFinishedAndPublishesTheFinalTotal   # single test
./mvnw package                             # build jar
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
- **`application/TrackingService`** holds the use cases: `subscribe`, `unsubscribe`, `check`, `checkActive`, `get`, `list`, and `events`. Each command loads the aggregate, runs, and appends with the version it loaded; on a `ConcurrencyException` it retries up to 5 times. The health call happens **outside** that loop, so a check that races an unsubscribe is dropped. After appending, every event whose `triggersDowntimeReport()` is true (`Downtime`, `InternalErrorHappened`, `TrackingFinished`) makes the service reload **all** its events up to that one, rehydrate the aggregate, and send `aggregate.report(trigger)` to the publisher. A publish failure is logged and does not undo the event. There is no retry, but each report carries the cumulative total, so the next one supersedes a missed one. `checkActive` checks every active service concurrently on virtual threads.
- **`infrastructure/`**: the adapters.
  - `persistence/InMemoryTrackingEventStore`: synchronized and lost on restart. A durable store only needs another `TrackingEventStore`.
  - `probe/HttpHealthProbe`: a `RestClient` GET with a connect/read timeout of `monitor.probe-timeout-ms`. It reads the JSON `status` only on a 200, and treats a non-JSON 200 as healthy.
  - `solana/SolanaMemoDowntimePublisher`: writes each report on chain as an **SPL Memo** transaction (`MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr`, present on every cluster including `solana-test-validator`) signed by the oracle key. The memo is compact JSON: `{"app":"uptime-monitor","serviceId","trigger","totalDowntimeMs","downtimeChecks","internalErrors","active","at"}`. Before each send it tops up from the faucet when the balance is below `oracle-min-lamports`. A fresh key's first report can fail before its airdrop lands. `LoggingDowntimePublisher` replaces it when `monitor.blockchain.enabled=false`. The Solana client (`Base58`, `Ed25519`, `OracleKey`, `SolanaTransaction`, `SolanaRpc`/`HttpSolanaRpc`) is a copy of `uptime-service`'s hand-rolled one; the modules share no build.
  - `scheduling/HealthCheckScheduler`: `@Scheduled(fixedRate = monitor.check-interval-ms)` → `checkActive()`. It is off when `monitor.scheduler.enabled=false`.
- **`interfaces/web/SubscriptionController`** under `/api/subscriptions`:
  - `POST {healthUrl, serviceId?}`: 201 with a summary. `healthUrl` must be an absolute http(s) URL (otherwise 400). `serviceId` is optional; send it to resume a finished UUID. A service that is already tracked gives 409.
  - `DELETE /{id}`: finishes tracking and returns the summary. An unknown id gives 404; a service that is no longer tracked gives 409.
  - `GET` lists all services. `GET /{id}` returns `active`, `startedAt`, `finishedAt`, `totalDowntimeMs`, `downtimeChecks`, `internalErrors` and `eventCount`. `GET /{id}/events` returns `{type, serviceId, occurredAt, httpStatus, detail}`, oldest first.
  - `ApiExceptionHandler` maps errors: `IllegalArgumentException` (including a bad UUID) → 400, `NoSuchElementException` → 404, `TrackingException` → 409.

Configuration (`application.properties`, bound to the `MonitorProperties` record): `monitor.check-interval-ms` (2000), `monitor.probe-timeout-ms` (1500; keep it below the interval), and `monitor.blockchain.{enabled, rpc-url (SOLANA_RPC_URL), rpc-timeout-ms, oracle-keypair (MONITOR_ORACLE_KEYPAIR, default .oracle-keypair.json, gitignored; blank = new key per start), oracle-min-lamports, oracle-airdrop-lamports}`. Time comes from the injected `Clock` bean.

## Testing notes

The tests need no network, chain or provider, and they never sleep.

- Unit tests per layer: `HealthCheckResultTest` and `ServiceTrackingTest` (domain); `TrackingServiceTest` (a fake probe, the in-memory store, a capturing publisher, and `support/MutableClock`, covering races, retries, and publish failures); `InMemoryTrackingEventStoreTest`; `HttpHealthProbeTest` (`MockRestServiceServer`, plus a real refused port and a silent socket for the timeout); `SolanaMemoDowntimePublisherTest` (mocked `SolanaRpc`, which checks the signed memo bytes); `HealthCheckSchedulerTest`; and the copied Solana client tests.
- `UptimeMonitorApplicationTests` is a full `@SpringBootTest` with MockMvc that uses `@MockitoBean` for `HealthProbe` and `SolanaRpc`. The `test` profile disables the scheduler, so tests call `TrackingService.check`/`checkActive` directly. `BlockchainDisabledTests` checks the logging-publisher wiring with the scheduler on.

Add tests for every new feature in the matching layer.
