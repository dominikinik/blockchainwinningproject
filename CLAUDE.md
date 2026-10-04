# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

This repository is a monorepo that will hold multiple independent modules, each written in whatever language suits it (Java, TypeScript, Rust/Anchor, …). Each top-level module covers one specific topic. Modules do not share a build system: build, test and run each one from inside its own directory with its own toolchain, and keep changes scoped to the module you are working on.

## Module documentation rules

- Every module has its own `CLAUDE.md` at its root (e.g. `uptime-service/CLAUDE.md`). It explains the module's purpose, its commands (build, run, test, single test), and its architecture. Module-specific details go there, not in this file.
- When you create a new module, write its `CLAUDE.md` as part of that change and add the module to the list below.
- When you change a module, update its `CLAUDE.md` in the same change if the change affects anything it describes: behavior, architecture, data flow, configuration, endpoints, or commands. Keep the docs in step with the code.

## Testing rules

- **Run the module's existing tests whenever you change it.** Before you call a change done, run the module's full suite (the command is in its `CLAUDE.md`) and make it pass.
- **Every new feature or bug fix comes with new tests in the same change.** Cover startup, every piece of functionality, and the edge cases (invalid input, empty or missing data, boundaries, error paths). Update existing tests when behavior changes on purpose.
- **Every module has its own test suite** that covers starting the module, all of its functionality, and its edge cases.
- **Tests stay inside one module.** Don't write cross-module or integration tests for now. Mock or fake the other modules, and the network, at the module boundary.
- **Keep tests lightweight.** They must be fast (a few seconds per module), deterministic, and need no running services from other modules. Drive time with fake or controllable clocks instead of sleeping.
- **The pre-commit pipeline must stay green.** `scripts/test-all.sh` runs every module's suite, and `.githooks/pre-commit` runs it before each commit and blocks the commit on any failure. Enable it once per clone with `git config core.hooksPath .githooks`. Don't bypass it with `--no-verify`. When you add a module, add its test command to `scripts/test-all.sh`.

## Modules

- `uptime-service/` (Java 21, Spring Boot): a health **provider** only. It exposes health endpoints (including `/api/health`), start/stop, and Swagger; no database. See [uptime-service/CLAUDE.md](uptime-service/CLAUDE.md).
- `uptime-monitor/` (Java 21, Spring Boot): the health **proxy** and `uptime-deal` oracle. For each registered deal it calls the provider's `/api/health` once per round of the deal's own on-chain interval and reports the result as that round's UP/DOWN `record_observation`. It registers deals (`/api/deals`), follows their acceptance, settles them, and serves `/api/deals` and an in-memory `/api/uptime`. It stores deals in `monitor-db`. See [uptime-monitor/CLAUDE.md](uptime-monitor/CLAUDE.md).
- `monitor-db/` (PostgreSQL 17, Docker Compose, port 5433): the database of `uptime-monitor`'s registered deals; the monitor-db module owns its schema and data. See [monitor-db/CLAUDE.md](monitor-db/CLAUDE.md).
- `frontend/` (React, TypeScript, Vite): the SLAna dashboard and Solana wallet UI. See [frontend/CLAUDE.md](frontend/CLAUDE.md).
- `uptime-deal/` (Rust, Anchor 1.1.2): a Solana program for an uptime agreement. The payer proposes it with a payment, and the provider accepts it with a guarantee, which starts the window. When reported uptime is above 99% the provider gets both deposits; otherwise the payer does. See [uptime-deal/CLAUDE.md](uptime-deal/CLAUDE.md).

## Shared environment

`Dockerfile` + `.devcontainer/` provide a Solana/Anchor environment (OtterSec Anchor image, Node 24, Rust 1.95, Surfpool on port 8899) and forward Vite on port 5173. The image has no JDK; run `uptime-service` on a Java 21 host or extend the container. To work on the host instead, run `scripts/setup-toolchain.sh`: it checks every tool the modules need and installs the missing Solana side (Rust 1.95, Agave CLI 3.1.10, Anchor 1.1.2 from crates.io, a dev keypair) at the Dockerfile's versions without downloading from GitHub, plus Maven 3.9.16 from Maven Central (linked as `~/.local/bin/mvn`; the Java modules have no Maven wrapper); `--check` only reports. The host has no Surfpool, so run `anchor test --validator legacy` there (uses `solana-test-validator`). The first `anchor build` still fetches Solana's platform-tools from GitHub. Java, Node and Docker are checked but not installed. The frontend proxies `/api/application` to uptime-service and the rest of `/api` to uptime-monitor during development. `scripts/run-deal-demo.sh` starts the validator, monitor-db, uptime-service (provider), uptime-monitor (oracle), and the frontend `/deal` page with a burner wallet. See [MANUAL_DEAL_TESTING.md](MANUAL_DEAL_TESTING.md) for setup, payout/refund steps, and troubleshooting. The program is also deployed on Devnet; [DEVNET_DEMO.md](DEVNET_DEMO.md) is the live-demo script there (real wallets, funding, step-by-step presentation with Solana Explorer, fallbacks). `cd frontend && npm run test:e2e` runs the same flow under Playwright. See [INTEGRATION_GAPS.md](INTEGRATION_GAPS.md) for the current frontend/backend boundary and remaining work, and [SLA_PRICING.md](SLA_PRICING.md) for the breach-probability, premium and cost-efficiency formulas (SIE/SBP/SEP/SPF/SSR/SCE), the planned AI forecasting (SRF), and their implementation plan.
