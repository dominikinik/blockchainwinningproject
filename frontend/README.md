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

## Current behavior

- Dashboard, SLA details, and monitoring are browseable without a wallet.
- Phantom and Solflare connections use Solana Devnet.
- Creating an SLA requires a connected wallet. The current service creates a local mock agreement; it does not sign a transaction or lock SOL.
- The ended Email API agreement demonstrates the settlement request state. Its mock settlement is handled by the service and stored locally; no SOL moves.
- New agreements and mock settlements persist in browser `localStorage`. Seed agreements, monitors, observations, and uptime history live in `src/mocks/data.ts`.
- Mock observation signatures are visual placeholders and do not link to Explorer.

## Anchor integration boundary

Page components call `src/services/solana/slaService.ts`. Replace that service's mock reads with program account reads and its `createSLA` / `settleSLA` methods with wallet signed Anchor instructions. Derive status, consensus, and actual settlement from program accounts. Replace the placeholder observation signatures with real transaction signatures before enabling Solana Explorer links. The UI's settlement projection remains explicitly informational.
