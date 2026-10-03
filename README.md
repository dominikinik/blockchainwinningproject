# SLAna monorepo

This repository holds the SLAna frontend, uptime service, and database. A Solana program is planned. The frontend lives in [`frontend/`](frontend/README.md).

## Frontend

From the repository root:

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

Vite serves the app at `http://localhost:5173`. The devcontainer forwards that port, along with port `8899` for Surfpool. Run `npm --prefix frontend run build` to check TypeScript and create a production build.

The Monitoring page can display the Java backend's service state through Vite's same-origin `/api` proxy. SLA uptime and settlement remain mocked. See [INTEGRATION_GAPS.md](INTEGRATION_GAPS.md) for the boundary and remaining work.

## Tests

`scripts/test-all.sh` runs every module's tests. To run it automatically before each commit, enable the pre-commit hook once per clone:

```bash
git config core.hooksPath .githooks
```

See [`uptime-db/CLAUDE.md`](uptime-db/CLAUDE.md) and [`uptime-service/CLAUDE.md`](uptime-service/CLAUDE.md) to run the backend modules.
