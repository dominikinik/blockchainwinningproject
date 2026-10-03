# SLAna monorepo

This repository holds the SLAna frontend, uptime service, database, and Solana uptime-deal program. The frontend lives in [`frontend/`](frontend/README.md).

## Frontend

From the repository root:

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

Vite serves the app at `http://localhost:5173`. The devcontainer forwards that port, along with port `8899` for Surfpool. Run `npm --prefix frontend run build` to check TypeScript and create a production build.

The Monitoring page displays the Java backend's service state and five-minute uptime timeline through Vite's same-origin `/api` proxy. SLA creation is a local demo; customer endpoint uptime and settlement remain mocked. See [INTEGRATION_GAPS.md](INTEGRATION_GAPS.md) for the minimum remaining work.

## Solana network configuration

The uptime service and frontend must use the **same RPC endpoint** for the `/deal` flow. Configure them independently with these environment variables:

| Component | Setting | Default |
| --- | --- | --- |
| `uptime-service` | `SOLANA_RPC_URL` | `http://127.0.0.1:8899` (Localnet) |
| `frontend` | `VITE_SOLANA_RPC_URL` | `https://api.devnet.solana.com` (Devnet) |

The `/deal` page compares its configured RPC URL with the URL reported by `GET /api/deals/config`. If they differ, it displays an error and disables deal creation.

For local development, set both values to the local validator URL:

```powershell
$env:SOLANA_RPC_URL = "http://127.0.0.1:8899"
$env:VITE_SOLANA_RPC_URL = "http://127.0.0.1:8899"
```

For a Devnet demo, set both to Devnet:

```powershell
$env:SOLANA_RPC_URL = "https://api.devnet.solana.com"
$env:VITE_SOLANA_RPC_URL = "https://api.devnet.solana.com"
```

Set each variable in the shell that starts its component, then restart that component. The uptime service reads `SOLANA_RPC_URL` as its RPC endpoint; Vite exposes `VITE_SOLANA_RPC_URL` to the browser. The Devnet demo also requires the uptime-deal program deployed to Devnet and funded demo wallets/oracle. The local demo script configures the frontend for Localnet automatically; see [`scripts/run-deal-demo.sh`](scripts/run-deal-demo.sh).

## Tests

`scripts/test-all.sh` runs every module's tests. To run it automatically before each commit, enable the pre-commit hook once per clone:

```bash
git config core.hooksPath .githooks
```

See [`uptime-db/CLAUDE.md`](uptime-db/CLAUDE.md) and [`uptime-service/CLAUDE.md`](uptime-service/CLAUDE.md) to run the backend modules.
