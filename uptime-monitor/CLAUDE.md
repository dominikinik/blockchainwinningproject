# CLAUDE.md — uptime-monitor

This file provides guidance to Claude Code (claude.ai/code) when working in the `uptime-monitor` module. Keep it up to date: if a change alters the module's behavior, architecture, configuration, or commands, update this file in the same change.

## Overview

A Spring Boot 4.1 / Java 21 service that is a stateless **proxy** between the health provider (`../uptime-service`) and the `uptime_deal` Solana program (`../uptime-deal`), whose **oracle** it is. Every 2 s it calls the provider's `GET /api/health` and sends the result to the program as the UP/DOWN `record_observation` of the round that just ended, on every active deal that names its oracle key. It finds those deals on chain on every check, so nothing is registered or stored: it has no database, never settles a deal (any wallet calls `settle_deal`), and a restart only loses the rounds it missed (unobserved rounds count as down on chain). It builds with an installed Maven 3.9 (`mvn`; `../scripts/setup-toolchain.sh` installs it) and has no Maven wrapper.

## Commands

Run these from `uptime-monitor/`:

```bash
mvn spring-boot:run                        # start on :8082 (UPTIME_MONITOR_PORT), relaying http://localhost:8080/api/health
MONITOR_BLOCKCHAIN_ENABLED=false mvn spring-boot:run      # probe and log only, send nothing
mvn test                                   # all tests (~5 s, no network, chain or provider needed)
mvn test -Dtest=HealthRelayTest            # one class
mvn test -Dtest=HealthRelayTest#aHealthyCheckRecordsTheRoundThatJustEndedAsUp      # single test
mvn package                                # build jar
```

Try it against the provider (`uptime-service` on :8080) and a validator on :8899:

```bash
curl localhost:8082/api/deals/config                  # programId, oracle, rpcUrl, checkIntervalSeconds for create_deal
curl -XPOST localhost:8080/api/application/stop       # every check from now on records a DOWN round
curl localhost:8082/api/uptime                        # last 5 minutes, one {time, down} per second
```

Swagger UI is at `/swagger-ui.html`, OpenAPI at `/v3/api-docs`, and the proxy's own health at `/actuator/health`.

## Architecture

Packages under `com.example.monitor`. Dependencies point inward: `interfaces` and `infrastructure` → `application` → `domain`. The domain and application layers have no Spring imports. `infrastructure/config/MonitorConfig` wires them.

- **`domain/`**
  - `HealthCheckResult` classifies one response: a 200 whose JSON `status` is `DOWN`/`OUT_OF_SERVICE`, or a 404, is `DOWN`. Any other 200 is `HEALTHY`. Any other status, or no response, is `INTERNAL_ERROR`. Only `HEALTHY` counts as UP; the other two are reported as DOWN.
  - Ports:
    - `HealthProbe` calls the endpoint once and never throws.
    - `DealChain` covers `activeDeals()`, `recordObservation(address, round, up)` and `ensureOracleFunded()`, plus the program id, the oracle address and the RPC URL.
  - `DealChain.ActiveDeal` is an accepted deal: its window start, round length, round count, and the program's per-round bitmap. `roundEndedBy(at)` returns the last round that ended at or before `at`: `floor((at - startsAt) / interval) - 1`, or `-1` before the first round ends and after the last.
- **`application/HealthRelay`** is the whole proxy. `relay()` takes the clock time, probes once, and records the result in `UptimeHistory`. It then lists the active deals and, for each one whose `roundEndedBy` round is not yet recorded on chain, sends `record_observation` with the result. It funds the oracle once before the first send of a tick. It never throws:
  - A listing failure skips the tick.
  - One deal's failed send doesn't stop the others.
  - Failed rounds are not retried, and they count as down on chain.

  The relay returns what it sent. With no `DealChain` (blockchain disabled) it only probes, records and logs.
- **`application/UptimeHistory`** serves `/api/uptime` from memory: the time of the first result, plus the DOWN results of the last `max-range-seconds`. A second is down before the first result or within `[t, t + interval)` of a DOWN result. The range is `[from, to]` inclusive and defaults to the last `monitor.history.default-range-seconds`, capped at `max-range-seconds`. A restart starts it over.
- **`infrastructure/`**
  - `probe/HttpHealthProbe`: a `RestClient` GET with a connect/read timeout of `monitor.probe-timeout-ms`. It reads the JSON `status` only on a 200, and treats a non-JSON 200 as healthy.
  - `solana/SolanaDealChain`:
    - `activeDeals()` makes one `getProgramAccounts` call with a memcmp of the oracle key at `DealProgram.ORACLE_OFFSET` (72). It keeps the accounts that decode as an active `Deal` naming this oracle, and skips proposals and undecodable data.
    - `recordObservation` signs the instruction with `OracleKey` and sends it.
    - `ensureOracleFunded` asks the faucet when the balance is below `oracle-min-lamports`, and never throws.
  - `DealProgram` holds the `Deal` account layout and the `record_observation` instruction (discriminator, `round` u32 LE, `up` u8; accounts oracle (signer), deal (writable)).
  - The Solana client (`Base58`, `Ed25519`, `OracleKey`, `SolanaTransaction`, `SolanaRpc`/`HttpSolanaRpc`) is hand-rolled. `SolanaRpc` covers only `getProgramAccounts`, `getLatestBlockhash`, `sendTransaction`, `getBalance` and `requestAirdrop`.
  - `scheduling/HealthCheckScheduler`: `@Scheduled(fixedRate = monitor.check-interval-ms)` → `relay()`. It is off when `monitor.scheduler.enabled=false`.
- **`interfaces/web/`**
  - `DealController` serves `GET /api/deals/config` → `{programId, oracle, rpcUrl, checkIntervalSeconds}`, which is what a wallet needs for `create_deal`. It returns 503 when the blockchain is disabled.
  - `UptimeController` serves `GET /api/uptime?from&to` → `[{time, down}]`.
  - `ApiExceptionHandler` maps `IllegalArgumentException` → 400.

Lombok is available (`optional`, version managed by Spring Boot, wired as an explicit annotation processor in `maven-compiler-plugin`, and excluded from the boot jar). `lombok.config` marks generated code `@lombok.Generated` so coverage tools skip it.

Configuration (`application.properties`, bound to the `MonitorProperties` record):

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
- `monitor.deal.program-id`
- `monitor.history.default-range-seconds` (300) and `monitor.history.max-range-seconds` (86400)

Time comes from the injected `Clock` bean.

## Testing notes

The tests need no network, chain or provider, and they never sleep.

- `HealthCheckResultTest` covers the domain.
- `HealthRelayTest` uses a fake probe, a fake `DealChain` and `support/MutableClock`. It covers:
  - the round each result reports
  - UP vs DOWN/unreachable/error
  - before the first round and after the last
  - already-recorded rounds
  - several deals
  - one failed send
  - an unreachable chain
  - no chain at all
- `UptimeHistoryTest` covers the in-memory per-second history, its default range and limits, and the dropping of old results.
- Adapter tests:
  - `HttpHealthProbeTest` uses `MockRestServiceServer`, a real refused port, and a silent socket for the timeout.
  - `SolanaDealChainTest` uses a mocked `SolanaRpc`. It covers the active-deal filter, the signed `record_observation` bytes and funding.
  - `DealProgramTest` and `support/DealFixtures` build `Deal` account data the way the program writes it.
  - `HealthCheckSchedulerTest`, plus the Solana client tests (`Base58Test`, `OracleKeyTest`, `SolanaTransactionTest`, `HttpSolanaRpcTest`).
- `UptimeMonitorApplicationTests` is a full `@SpringBootTest` with MockMvc and `@MockitoBean` for `HealthProbe` and `SolanaRpc`. The `test` profile disables the scheduler, so tests call `HealthRelay.relay()` directly. It checks:
  - there is no database or scheduler
  - `/api/deals/config`
  - a relay sending to an on-chain deal
  - `/api/uptime` and its 400s
  - the removed `/api/subscriptions` and `/api/deals` registry endpoints are gone
- `BlockchainDisabledTests` checks that no Solana beans exist, `/api/deals/config` returns 503, and the relay with the real probe and scheduler only records.

Add tests for every new feature in the matching layer.
