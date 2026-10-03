# CLAUDE.md — frontend

This module is the SLAna React/TypeScript UI. Keep this file in step with changes to its behavior, architecture, configuration, or commands.

## Commands

Run from `frontend/`:

```bash
npm install
npm run dev       # Vite on port 5173
npm run build     # TypeScript check and production build
npm run preview
```

There is no standalone frontend test script yet.

## Data flow

- `src/services/solana/slaService.ts` is the SLA data boundary. Its agreement, monitor, observation, and settlement methods currently use fixtures in `src/mocks/data.ts` and browser `localStorage`.
- React must not determine SLA success, consensus, or actual settlement. When the Anchor program is available, replace the mock service methods with wallet-signed instructions and program account reads.
- `src/services/uptime/uptimeService.ts` reads only `GET /api/application/state` from the Java backend. This is the backend's own logical state, shown on the Monitoring page. It does not score a customer's SLA.
- The backend's `/api/uptime` history remains unused; SLA uptime and monitor observations stay mocked until endpoint-specific monitoring exists.

## Same-origin development API

`vite.config.ts` proxies `/api` to `http://localhost:8080` by default. Set `SLANA_UPTIME_SERVICE_TARGET` before starting Vite to change the target (for example, `http://host.docker.internal:8080` when Vite runs in the devcontainer and Java runs on the host). Browser calls use relative `/api` URLs, so the Java service needs no development CORS setting. Production hosting needs equivalent same-origin routing.

The frontend remains usable when the Java service is stopped: the Monitoring page shows `Backend unavailable`, while demo SLA data remains visible.
