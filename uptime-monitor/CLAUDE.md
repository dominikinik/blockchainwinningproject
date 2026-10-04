# CLAUDE.md — uptime-monitor

This file provides guidance to Claude Code (claude.ai/code) when working in the `uptime-monitor` module. Keep it up to date: if a change alters the module's behavior, architecture, configuration, or commands, update this file in the same change.

## Overview

A Spring Boot 4.1 / Java 21 service that is the health **proxy** between the provider (`../uptime-service`) and the `uptime_deal` Solana program (`../uptime-deal`), and that program's **oracle**. Every 2 s it calls the provider's `GET /api/health` and hands the result to the deal oracle, which sends it as the UP/DOWN `record_observation` of the round that just ended on each registered, accepted deal. Deals are registered with `POST /api/deals` and stored in PostgreSQL from the sibling `monitor-db` module. The oracle follows each proposal until it is accepted on chain and settles each deal once the on-chain counters prove a breach or the window and grace are over. It builds with an installed Maven 3.9 (`mvn`; `../scripts/setup-toolchain.sh` installs it) and has no Maven wrapper.

## Commands

Running the service needs its database (see `../monitor-db/`). Tests don't:

```bash
docker compose -f ../monitor-db/docker-compose.yml up -d --wait   # PostgreSQL on :5433
```

Run these from `uptime-monitor/`:

```bash
mvn spring-boot:run                        # start on :8082 (UPTIME_MONITOR_PORT), relaying http://localhost:8080/api/health
MONITOR_BLOCKCHAIN_ENABLED=false mvn spring-boot:run      # probe and log only; no oracle, /api/deals returns 503
mvn test                                   # all tests (~6 s, no database, network, chain or provider needed)
mvn test -Dtest=HealthRelayTest            # one class
mvn test -Dtest=DealServiceTest#reportsCompletedRoundsAndLeavesPayoutDecisionToTheContract      # single test
mvn package                                # build jar
```

Try it against the provider (`uptime-service` on :8080) and a validator on :8899:

```bash
curl localhost:8082/api/deals/config                  # programId, oracle, rpcUrl, checkIntervalSeconds for create_deal
curl -XPOST localhost:8082/api/deals -H 'Content-Type: application/json' -d '{"address":"<deal>"}'   # register
curl localhost:8082/api/deals/<deal>                  # PROPOSED / ACTIVE / SETTLED / FAILED / CANCELLED
curl -XPOST localhost:8080/api/application/stop       # every check from now on records a DOWN round
curl localhost:8082/api/uptime                        # last 5 minutes, one {time, down} per second
```

Swagger UI is at `/swagger-ui.html`, OpenAPI at `/v3/api-docs`, and the proxy's own health at `/actuator/health`.

## Architecture

Packages under `com.example.monitor`. Dependencies point inward: `interfaces` and `infrastructure` → `application` → `domain`. The domain and application layers have no Spring imports. `infrastructure/config/MonitorConfig` wires them.

- **`domain/`**
  - `HealthCheckResult` classifies one response:
    - A 200 whose JSON `status` is `DOWN`/`OUT_OF_SERVICE`, or a 404, is `DOWN`.
    - Any other 200 is `HEALTHY`.
    - Any other status, or no response, is `INTERNAL_ERROR`.

    Only `HEALTHY` counts as UP.
  - `HealthProbe` is a port: it calls the endpoint once and never throws.
  - `ServiceId` is the UUID that deals are linked to: the relayed service's `monitor.service-id`.
- **`domain/deal/`**: the deal oracle's model.
  - `UptimeDeal` is immutable; every transition returns a copy.
    - `PROPOSED` until the recipient accepts on chain, which sets the window start.
    - `ACTIVE` until the settlement it sent is confirmed (`SETTLED`), or the account is found closed (`SETTLED`/`CANCELLED`), or it gives up (`FAILED`).
  - Ports:
    - `UptimeDealRepository`
    - `DealChain`: read a deal account (`ChainDeal`: terms, counters, per-round bitmap), send `record_observation` and `settle_deal`, check a signature, find the closure of a vanished deal, fund the oracle.
- **`application/HealthRelay`** is the proxy loop. `relay()`:
  1. takes the clock time;
  2. probes `monitor.health-url` once;
  3. records the result in `UptimeHistory`;
  4. hands it to every `HealthListener`, which today is only `DealService` and none when the blockchain is off.

  It never throws, and a listener's failure is logged.
- **`application/DealService`** (a `HealthListener`) is the oracle. Its methods are synchronized, because the relay, the scheduler and HTTP requests all change deals.
  - `register(address, serviceId?)`:
    - The deal is linked to the relayed service; another `serviceId` is a 400.
    - It reads the account. The account must be owned by the program and name this oracle. Its duration must be in `[1, monitor.deal.max-duration-seconds]` and its round interval must equal the check interval.
    - It funds the oracle and stores the deal (`PROPOSED` or `ACTIVE`). A duplicate is a 409.
  - `onHealthResult(at, up)` queues the observation and reports it on each accepted deal: round `floor((at - startsAt) / interval) - 1`, skipped when it is before round 0, past the last round, or already recorded on chain. A failed send stays queued in memory and is retried on the next result or tick, and the queue is lost on restart.
  - `settleDue()` runs every `monitor.deal.poll-interval-ms`. It retries queued observations, re-reads each proposal to pick up its acceptance, and sends `settle_deal` for each accepted deal once `canSettleEarly()` (the DOWN rounds prove the threshold unreachable) or the window plus `max(settle-grace, 10)` seconds has passed. It then confirms, resends or fails the transaction. A vanished account is resolved from its recent closing events. Anyone may call `settle_deal`; the program computes the payout from its own counters.
- **`application/UptimeHistory`** serves `/api/uptime` from memory: the time of the first result, plus the DOWN results of the last `max-range-seconds`.
  - A second is down before the first result, or within `[t, t + interval)` of a DOWN result.
  - The range is `[from, to]` inclusive. It defaults to the last `monitor.history.default-range-seconds` and is capped at `max-range-seconds`.
  - A restart starts it over.
- **`infrastructure/`**
  - `probe/HttpHealthProbe`: a `RestClient` GET with a connect/read timeout of `monitor.probe-timeout-ms`. It reads the JSON `status` only on a 200, and treats a non-JSON 200 as healthy.
  - `persistence/JdbcUptimeDealRepository`: `JdbcClient` on the `uptime_deal` table owned by `../monitor-db`, one row per deal, updated in place. A duplicate address raises `DealAlreadyRegisteredException`.
  - `solana/SolanaDealChain` implements `DealChain` on `SolanaRpc`, `OracleKey`, `SolanaTransaction` and `DealProgram`. `DealProgram` holds the program's binary layout: the `Deal` account, the `record_observation` and `settle_deal` instructions, and the `DealSettled`/`DealCancelled` events. The Solana client (`Base58`, `Ed25519`, `OracleKey`, `SolanaTransaction`, `SolanaRpc`/`HttpSolanaRpc`) is hand-rolled.
  - `scheduling/HealthCheckScheduler`: `@Scheduled(fixedRate = monitor.check-interval-ms)` → `relay()`. `scheduling/DealSettlementScheduler` → `settleDue()`. Both are off when `monitor.scheduler.enabled=false`.
- **`interfaces/web/DealController`** under `/api/deals`. The JSON shape is the one the frontend reads, plus `serviceId`.
  - `GET /config` returns `{programId, oracle, rpcUrl, checkIntervalSeconds}`.
  - `POST {address, serviceId?}` returns 201.
  - `GET` returns all deals, most recently proposed first. `GET /{address}` returns one deal; `startsAt`/`endsAt` are null while `PROPOSED`.
  - Every endpoint returns 503 when `monitor.blockchain.enabled=false`.
- **`interfaces/web/UptimeController`** serves `GET /api/uptime?from&to` → `[{time, down}]`.
- `ApiExceptionHandler` maps errors:
  - `IllegalArgumentException` → 400
  - `NoSuchElementException` → 404
  - `DealAlreadyRegisteredException` → 409
  - `SolanaRpcException` → 502

Lombok is available (`optional`, version managed by Spring Boot, wired as an explicit annotation processor in `maven-compiler-plugin`, and excluded from the boot jar). `lombok.config` marks generated code `@lombok.Generated` so coverage tools skip it.

Persistence: `spring.datasource.*` defaults to `jdbc:postgresql://localhost:5433/monitor` (user/password `monitor`). Override it with `MONITOR_DB_URL`, `MONITOR_DB_USER` and `MONITOR_DB_PASSWORD`. If you change the schema, update these together:

- `monitor-db/init/02-schema.sh`
- `JdbcUptimeDealRepository`
- `src/test/resources/schema.sql`

Configuration (`application.properties`, bound to the `MonitorProperties` record):

- `monitor.service-id` (`MONITOR_SERVICE_ID`, default `00000000-0000-0000-0000-000000008080`)
- `monitor.health-url` (`MONITOR_HEALTH_URL`, default `http://localhost:8080/api/health`)
- `monitor.check-interval-ms` (2000)
- `monitor.probe-timeout-ms` (1500; keep it below the interval)
- `monitor.blockchain.*`:
  - `enabled` (`MONITOR_BLOCKCHAIN_ENABLED`)
  - `rpc-url` (`SOLANA_RPC_URL`)
  - `rpc-timeout-ms`
  - `oracle-keypair` (`MONITOR_ORACLE_KEYPAIR`, default `.oracle-keypair.json`, gitignored; blank = new key per start)
  - `oracle-min-lamports`
  - `oracle-airdrop-lamports`
- `monitor.deal.*`:
  - `program-id`
  - `max-duration-seconds` (3600)
  - `settle-grace-seconds` (2)
  - `poll-interval-ms` (500)
  - `max-settle-attempts` (10)
  - `confirm-timeout-seconds` (30)
- `monitor.history.default-range-seconds` (300) and `monitor.history.max-range-seconds` (86400)

Time comes from the injected `Clock` bean.

## Testing notes

The tests need no database, network, chain or provider, and they never sleep. Spring tests run on in-memory H2 in PostgreSQL mode, using `src/test/resources/schema.sql`, a copy of the `monitor-db` tables.

- Domain: `HealthCheckResultTest`, plus `UptimeDealTest` for the deal lifecycle.
- Application:
  - `HealthRelayTest`: a fake probe, listeners and `support/MutableClock`. It covers each result reaching every listener, DOWN/unreachable/error, a failing listener, and the history.
  - `DealServiceTest`: a mocked `DealChain` and `support/InMemoryUptimeDealRepository`. It covers the round reported, DOWN rounds, retry after a failed send, proposals and early results, no early settlement, and registration rules.
  - `UptimeHistoryTest`.
- Persistence: `UptimeDealRepositoryContract` runs against `JdbcUptimeDealRepository` (H2 behind a Hikari pool) and the in-memory fake.
- Adapters:
  - `HttpHealthProbeTest`: `MockRestServiceServer`, a real refused port, and a silent socket.
  - `SolanaDealChainTest`: a mocked `SolanaRpc`. It covers reads, the signed `record_observation`/`settle_deal` bytes, statuses and closures, and funding.
  - `DealProgramTest`: `support/DealFixtures` builds `Deal` account data and event logs the way the program writes them.
  - `HealthCheckSchedulerTest` and the Solana client tests.
- `UptimeMonitorApplicationTests` is a full `@SpringBootTest` with MockMvc and `@MockitoBean` for `HealthProbe` and `SolanaRpc`. The `test` profile disables both schedulers, so tests call `HealthRelay.relay()` and `DealService.settleDue()` directly. It covers:
  - the configured components
  - registering a deal, after which a relayed result reaches it on chain
  - proposals
  - deal error mapping
  - `/api/uptime`
  - the removed `/api/subscriptions`
- `BlockchainDisabledTests` checks that no Solana beans or oracle exist, that `/api/deals` returns 503, and that the relay, with the real probe and scheduler, only records.

Add tests for every new feature in the matching layer.
