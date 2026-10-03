# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

This repository is a monorepo that will hold multiple independent modules, each written in whatever language suits it (Java, TypeScript, Rust/Anchor, …). Each top-level module covers one specific topic. Modules do not share a build system: build, test and run each one from inside its own directory with its own toolchain, and keep changes scoped to the module you are working on.

## Module documentation rules

- Every module has its own `CLAUDE.md` at its root (e.g. `uptime-service/CLAUDE.md`). It explains the module's purpose, its commands (build, run, test, single test), and its architecture. Module-specific details go there, not in this file.
- When you create a new module, write its `CLAUDE.md` as part of that change and add the module to the list below.
- When you change a module, update its `CLAUDE.md` in the same change if the change affects anything it describes: behavior, architecture, data flow, configuration, endpoints, or commands. Keep the docs in step with the code.

## Modules

- `uptime-service/` (Java 21, Spring Boot): a service that monitors its own health and records per-second uptime history. See [uptime-service/CLAUDE.md](uptime-service/CLAUDE.md).
- `uptime-db/` (PostgreSQL 17, Docker Compose): the local database for `uptime-service`, which owns its schema and data. See [uptime-db/CLAUDE.md](uptime-db/CLAUDE.md).
- `frontend/` (React, TypeScript, Vite): the SLAna dashboard and Solana wallet UI. See [frontend/CLAUDE.md](frontend/CLAUDE.md).

## Shared environment

`Dockerfile` + `.devcontainer/` provide a Solana/Anchor environment (OtterSec Anchor image, Node 24, Rust 1.95, Surfpool on port 8899) and forward Vite on port 5173. The image has no JDK; run `uptime-service` on a Java 21 host or extend the container. The frontend proxies `/api` to that service during development. See [INTEGRATION_GAPS.md](INTEGRATION_GAPS.md) for the current frontend/backend boundary and remaining work.
