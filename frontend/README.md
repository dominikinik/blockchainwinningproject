# SLAna frontend

An API SLA dashboard prototype built with React, TypeScript, Vite, Tailwind CSS, and Solana Wallet Adapter.

## Run locally

From the repository root:

```bash
cd frontend
npm install
npm run dev
```

Open the URL printed by Vite (normally `http://localhost:5173`). Run `npm run build` for the TypeScript and production build check.
The devcontainer forwards port `5173` for Vite and `8899` for Surfpool.

The Monitoring page reads the Java service's own state and per-second uptime history through Vite's `/api` proxy. Start `uptime-db` and `uptime-service` to see the live green/red five-minute timeline. The proxy targets `http://localhost:8080` by default; set `SLANA_UPTIME_SERVICE_TARGET=http://host.docker.internal:8080` if Vite runs in the devcontainer and Java runs on the host. Customer SLA uptime data remains mocked. Production deployment needs a same-origin `/api` reverse proxy.

For that devcontainer setup, start Vite with:

```bash
SLANA_UPTIME_SERVICE_TARGET=http://host.docker.internal:8080 npm run dev
```

## Current behavior

- Dashboard, SLA details, and monitoring are browseable without a wallet.
- Phantom and Solflare connections use Solana Devnet.
- Creating an SLA requires a connected wallet. The current service creates a local mock agreement; it does not sign a transaction or lock SOL.
- The ended Email API agreement demonstrates the settlement request state. Its mock settlement is handled by the service and stored locally; no SOL moves.
- New agreements and mock settlements persist in browser `localStorage`. Seed agreements, observations, and SLA uptime history live in `src/mocks/data.ts`.
- Mock observation signatures are visual placeholders and do not link to Explorer.
- The Monitoring page displays the backend's own live state and recorded uptime timeline. The backend does not monitor the API endpoint entered on Create SLA yet.

## Anchor integration boundary

Page components call `src/services/solana/slaService.ts`. Replace that service's mock reads with program account reads and its `createSLA` / `settleSLA` methods with wallet signed Anchor instructions. Derive status, consensus, and actual settlement from program accounts. Replace the placeholder observation signatures with real transaction signatures before enabling Solana Explorer links. The UI's settlement projection remains explicitly informational.
