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

- `uptime-service/` (Java 21, Spring Boot): a service that monitors its own health and records per-second uptime history. See [uptime-service/CLAUDE.md](uptime-service/CLAUDE.md).
- `uptime-db/` (PostgreSQL 17, Docker Compose): the local database for `uptime-service`, which owns its schema and data. See [uptime-db/CLAUDE.md](uptime-db/CLAUDE.md).
- `frontend/` (React, TypeScript, Vite): the SLAna dashboard and Solana wallet UI. See [frontend/CLAUDE.md](frontend/CLAUDE.md).

## Shared environment

`Dockerfile` + `.devcontainer/` provide a Solana/Anchor environment (OtterSec Anchor image, Node 24, Rust 1.95, Surfpool on port 8899) and forward Vite on port 5173. The image has no JDK; run `uptime-service` on a Java 21 host or extend the container. To work on the host instead, run `scripts/setup-toolchain.sh`: it checks every tool the modules need and installs the missing Solana side (Rust 1.95, Agave CLI 3.1.10, Anchor 1.1.2 from crates.io, a dev keypair) at the Dockerfile's versions without downloading from GitHub; `--check` only reports. The host has no Surfpool, so run `anchor test --validator legacy` there (uses `solana-test-validator`). The first `anchor build` still fetches Solana's platform-tools from GitHub. Java, Node and Docker are checked but not installed. The frontend proxies `/api` to that service during development. See [INTEGRATION_GAPS.md](INTEGRATION_GAPS.md) for the current frontend/backend boundary and remaining work.
