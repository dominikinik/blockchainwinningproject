# CLAUDE.md — uptime-service

This file provides guidance to Claude Code (claude.ai/code) when working in the `uptime-service` module. Keep it up to date: if a change alters the module's behavior, architecture, configuration, or commands, update this file in the same change.

## Overview

A Spring Boot 4.1 / Java 21 service that monitors its own health, records per-second uptime history for the dashboard, and reports per-round UP/DOWN observations to the `uptime_deal` Solana program, which settles SLAs on chain. It builds on the host machine; the repo's devcontainer has no JDK.

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
3. **`monitor/UptimeSampler`** has two `@Scheduled` jobs. `sample()` runs every `uptime.sample-interval-ms` (10 ms) and reads the health indicator into in-memory per-epoch-second buckets. `flush()` runs every `uptime.flush-interval-ms` (1 s) and persists every *completed* second; the current second stays in memory while it is still filling. A second counts as up only if **every** sample in it was UP. The scheduler pool size is 5 (`spring.task.scheduling.pool.size`) so that sampling, flushing and the three deal jobs (`observe`, `advance`, `discover`) don't block each other.
4. **`uptime/UptimeRecord`** stores one row per second, keyed by the second's start `Instant` (`ts` column, UTC, truncated).
5. **`uptime/UptimeQueryService`** backs `GET /api/uptime` (an inclusive `[from, to]` range, one point per second) and `GET /api/uptime/at`. **Any second with no record is reported as down**, so time when the process was off counts as downtime. The range defaults to the last `uptime.default-range-seconds` seconds and is capped at `uptime.max-range-seconds`. An invalid range throws `IllegalArgumentException`, which `web/ApiExceptionHandler` maps to a 400 `ProblemDetail`.
6. **Uptime deals** (`deal/`, `solana/`, `web/DealController`): this service is the **monitor** (oracle) of the `uptime_deal` Solana program (`../uptime-deal`). **Monitors observe; the program decides.** The service reports one UP/DOWN observation per round and then triggers settlement. It never calculates the SLA result, never sends uptime figures or a verdict, and never decides payouts: `settle_deal` has no arguments, and the program judges the SLA from the counters in the deal account. `DealService` does not depend on `UptimeQueryService` or the repository, so deleting the uptime database changes nothing about settlement (`DealServiceTest.theMonitorHasNoAccessToTheUptimeDatabase`).
   - `GET /api/deals/config` returns the program id, the oracle key and the RPC URL.
   - `POST /api/deals {address}` reads the `Deal` account over RPC. It must be owned by the program and name this oracle. The terms come from the chain, never from the caller. Any `durationSeconds` in the body is ignored. The service reads the window (`starts_at`, `duration_seconds`), `check_interval_seconds`, `min_uptime_bps`, `total_rounds`, the payment, the guarantee and the counters; the account layout is in `DealProgram`. An on-chain duration of 0 or above `deal.max-duration-seconds` is rejected (400). A deal whose provider hasn't locked its guarantee is tracked as `AWAITING_PROVIDER` and becomes `ACTIVE` when the account does.
   - `DealService.discover()` (`@Scheduled`, at startup and every `deal.discover-interval-ms`) lists the program's accounts that name this oracle (`getProgramAccounts` with a `memcmp` on `DealProgram.ORACLE_OFFSET`) and tracks the new ones. After a restart the service resumes monitoring every open deal, with no registration and no database. Rounds it missed while down are never reported and count as down on chain.
   - `DealService.observe()` (`@Scheduled`, every `deal.observe-interval-ms`, 200 ms) samples the health indicator. For every active deal inside its window, it adds the sample to the current round in that deal's `RoundLog`. A round is UP only if **every** sample in it was UP. It makes no RPC calls.
   - `DealService.advance()` (`@Scheduled`, every `deal.poll-interval-ms`) handles each open deal in turn:
     - It re-reads the account, refreshing the window, the status and the on-chain `upChecks` / `downChecks` shown by the API.
     - Until the program's observation grace closes (`endsAt + DealProgram.OBSERVATION_GRACE_SECONDS`, 10 s), it sends `record_observation(round, up)` for each witnessed round that has ended locally and that the chain's bitmap doesn't hold yet. A round still missing `deal.observation-retry-ms` after its send is resent. A send that fails, for example with `RoundNotEnded` because the chain clock lags, is logged and retried. The program rejects duplicates, so a resend can't double-count.
     - From `endsAt + 10 s + deal.settle-grace-seconds` it sends `settle_deal`, signed by the oracle only as the fee payer. Anyone may send it. On later ticks it checks the signature status, then reads the verdict and final counters from the `DealSettled` event. Failed sends are retried up to `deal.max-settle-attempts`, and an unconfirmed send is resent after `deal.confirm-timeout-seconds`. An RPC failure counts as a settlement attempt only once settlement is due.
   - When the deal account is gone, the service reads the last 10 signatures of the deal address (`getSignaturesForAddress`) and their logs. It looks for a `DealSettled` event (-> `SETTLED`: someone else settled it, or a send of ours threw but landed) or a `DealCancelled` event (-> `CANCELLED`: the payer withdrew a deal the provider never accepted). The event must **name this deal** (`DealProgram.closedBy`); events of other deals are ignored. If none is found, the deal is `FAILED`.
   - `GET /api/deals/{address}` and `GET /api/deals` return `TrackedDeal`s (`AWAITING_PROVIDER` / `ACTIVE` / `SETTLED` / `FAILED` / `CANCELLED`). Each has the terms, the last-read on-chain counters, `observationsSent`, the verdict and the settlement signature. It is a display copy; the account and its events are the authority. Settlement is permissionless, so a `FAILED` deal can still be settled by anyone, for example the frontend's "Settle now".
   - The Solana client is hand-rolled, with no SDK:
     - `Base58`;
     - `OracleKey`: JDK Ed25519. It reads a Solana CLI keypair JSON from `deal.oracle-keypair` (default `.oracle-keypair.json`, gitignored) and creates it if missing, with owner-only `rw-------` permissions where the filesystem is POSIX. A blank path means a new key on every start;
     - `SolanaTransaction`: compiles and signs legacy messages;
     - `SolanaRpc` / `HttpSolanaRpc`: JSON-RPC over `RestClient`, including `getProgramAccounts`.

     `deal.rpc-url` defaults to `SOLANA_RPC_URL` or `http://127.0.0.1:8899`. Every RPC call has a connect and read timeout of `deal.rpc-timeout-ms` (default 10000, set in `DealConfig`), so a stalled node raises `SolanaRpcException` instead of blocking the loop.
   - On localnet/devnet the service asks the faucet for `deal.oracle-airdrop-lamports` when the oracle holds less than `deal.oracle-min-lamports` (0 disables this). It checks when a deal is registered or discovered. The oracle pays one fee per round per deal, plus the settlement fee.
   - Errors map to problems: `IllegalArgumentException` → 400, `NoSuchElementException` → 404, `DealAlreadyRegisteredException` (duplicate) → 409, `SolanaRpcException` → 502. Any other exception, including `IllegalStateException`, is an unhandled 500.

Configuration is bound through the `UptimeProperties` record (`uptime.*` in `application.properties`). Time comes from an injected `Clock` bean (`ClockConfig`). Use that bean instead of calling `Instant.now()` in production code.

Persistence (history for the dashboard and API only; never read for deal settlement) uses PostgreSQL from the sibling **`uptime-db`** module (Docker Compose, `postgres:17-alpine`, user/password `uptime`). That module owns the schema (`uptime-db/init/`, which runs only when `uptime-db/data/` is empty) and the data files (`uptime-db/data/`, gitignored). The service runs with `ddl-auto=validate`, so if you change the schema, update both `init/02-schema.sh` and `UptimeRecord`, then recreate the data directory. History survives restarts. You can override the connection with `UPTIME_DB_URL`, `UPTIME_DB_USER`, and `UPTIME_DB_PASSWORD`. Tests use the `test` profile, which runs on in-memory H2 instead (see Testing notes).

Handling of rejected DB transactions is an open TODO on both paths: writes in `UptimeSampler.flush()` (drained buckets are lost on failure) and reads in `UptimeQueryService` (currently a 500).

## Testing notes

Tests are self-contained and run on in-memory H2 in PostgreSQL mode (`src/test/resources/application-test.properties`), so they need no `uptime-db` container. `src/test/resources/schema.sql` copies the table from `uptime-db/init/02-schema.sh`, and `ddl-auto=validate` still checks `UptimeRecord` against it. If you change the schema, update all three.

- **Unit tests** (no Spring) cover `state/`, `monitor/UptimeSampler` and `uptime/UptimeQueryService`, using a Mockito-mocked repository and `support/MutableClock`. Move the clock by hand instead of using `Thread.sleep`.
- **`UptimeServiceApplicationTests`** is a full `@SpringBootTest` with MockMvc. It checks startup, bean wiring and the scheduled jobs, and calls every HTTP endpoint, including the error cases. The real schedulers run during it, so the records it writes use dates in 2000 to avoid collisions. Tests share the singleton `ApplicationStateService`, and `@AfterEach` calls `state.start()` to reset it. Any new test that toggles the state must leave it UP.
- Deal code is unit-tested without a chain:
  - `DealServiceTest` (mocked `SolanaRpc`, a real `ApplicationStateService` for health, and `MutableClock`). It covers observing and reporting rounds, resends, unwitnessed rounds, pending deals, the settlement timing, a `settle_deal` that carries only its discriminator, verdicts, history lookups, discovery, a restarted instance settling from on-chain counters alone, and the absence of any database dependency.
  - `RoundLogTest`, `DealProgramTest`, `SolanaTransactionTest`, `OracleKeyTest` (RFC 8032 vector), `Base58Test`, and `HttpSolanaRpcTest` (`MockRestServiceServer`, plus a local silent `ServerSocket` for the timeout). `support/DealFixtures` builds account data (`deal(...)` builder: window, guarantee, threshold, recorded rounds) and event logs the way the program writes them. The test profile uses an in-memory oracle key with no faucet, and `UptimeServiceApplicationTests` replaces `SolanaRpc` with a `@MockitoBean`. The real-chain flow is covered by the frontend's Playwright e2e (`../frontend/e2e`).

Add tests for every new feature in the matching layer: unit tests for logic, and `UptimeServiceApplicationTests` for new endpoints or configuration.
