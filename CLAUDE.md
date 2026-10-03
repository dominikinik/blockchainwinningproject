# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

This repository is a monorepo that will hold multiple independent modules, each written in whatever language suits it (Java, TypeScript, Rust/Anchor, …). Each top-level module covers one specific topic. Modules do not share a build system: build, test and run each one from inside its own directory with its own toolchain, and keep changes scoped to the module you are working on.

## Module documentation rules

- Every module has its own `CLAUDE.md` at its root (e.g. `uptime-service/CLAUDE.md`). It explains the module's purpose, its commands (build, run, test, single test), and its architecture. Module-specific details go there, not in this file.
- When you create a new module, write its `CLAUDE.md` as part of that change and add the module to the list below.
- When you change a module, update its `CLAUDE.md` in the same change if the change affects anything it describes: behavior, architecture, data flow, configuration, endpoints, or commands. Keep the docs in step with the code.

## Modules

- `uptime-service/` (Java 21, Spring Boot): a modular monolith with checking, aggregation/history and explicit tracking lifecycle modules, recording session-scoped uptime summaries and typed bad-event runs. See [uptime-service/CLAUDE.md](uptime-service/CLAUDE.md).
- `uptime-db/` (PostgreSQL 17, Docker Compose): manages the local database, schema migrations and data for `uptime-service`. See [uptime-db/CLAUDE.md](uptime-db/CLAUDE.md).

## Shared environment

`Dockerfile` + `.devcontainer/` provide a Solana/Anchor bootcamp dev environment (OtterSec Anchor image, Node 24, Rust 1.95, Surfpool on port 8899). Its comments and the root `.gitignore` refer to `scripts/` and `diamond-hands/` (TypeScript snapshots and an Anchor workspace); neither module exists in the repo yet. The image has no JDK.
