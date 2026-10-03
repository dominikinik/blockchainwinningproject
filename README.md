# SLAna monorepo

This repository holds the SLAna frontend, uptime service, database, and an Anchor Solana program for uptime-conditioned escrow deals. The frontend lives in [`frontend/`](frontend/README.md).

## Frontend

From the repository root:

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

Vite serves the app at `http://localhost:5173`. The devcontainer forwards that port, along with port `8899` for Surfpool. Run `npm --prefix frontend run build` to check TypeScript and create a production build.

The Monitoring page displays the Java backend's service state and five-minute uptime timeline through Vite's same-origin `/api` proxy. The `/deal` page creates a real on-chain uptime deal and the Java service settles it from its recorded health history. See [MANUAL_DEAL_TESTING.md](MANUAL_DEAL_TESTING.md) for the local payout/refund walkthrough and Devnet switch checklist. SLA creation elsewhere in the dashboard remains a local demo; customer endpoint uptime and settlement remain mocked. See [INTEGRATION_GAPS.md](INTEGRATION_GAPS.md) for the remaining integration work.

## Tests

`scripts/test-all.sh` runs every module's tests. To run it automatically before each commit, enable the pre-commit hook once per clone:

```bash
git config core.hooksPath .githooks
```

See [`uptime-db/CLAUDE.md`](uptime-db/CLAUDE.md) and [`uptime-service/CLAUDE.md`](uptime-service/CLAUDE.md) to run the backend modules.
