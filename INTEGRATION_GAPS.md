# SLAna: minimum integration work

## Available now

- The Monitoring page reads the Java service's own `GET /api/application/state` and `GET /api/uptime?from=...&to=...` endpoints. The timeline shows recorded `UP` or `DOWN` seconds for the **Java service**, refreshing every 10 seconds. It does not measure customer API endpoints.
- Vite proxies relative `/api` requests to `uptime-service` during development, so the browser does not need CORS. The target is `http://localhost:8080` by default, or `SLANA_UPTIME_SERVICE_TARGET`.
- The Create SLA page calls `frontend/src/services/solana/slaService.ts#createSLA`. That method only saves a demo agreement in browser `localStorage`. It does not call a backend, create a Solana account, or lock SOL.

## Minimum missing API for customer endpoint uptime

1. Add a way to register the endpoint and schedule from an agreement: `POST /api/monitoring/targets` with an SLA/program account ID, HTTPS endpoint, check interval, and timeout. Return a stable target ID. The backend must then check and persist that target's results. The current sampler checks only the Java service itself.
2. Add `GET /api/monitoring/targets/{targetId}/uptime?from=...&to=...` returning timestamped `UP`/`DOWN` samples (or the existing `{time, down}` shape). This lets the frontend show a timeline for the SLA's endpoint. Keep the Java service's own `/api/uptime` separate.

These endpoints provide a basic single-service uptime view. Independent signed monitors, consensus, and verifiable observations are later work; the current Java service cannot supply them.

## Solana integration, separate from the Java API

- Implement the Anchor create/escrow instruction and account reads, then replace the mock `createSLA` and read methods in `slaService.ts`. The wallet must sign the transaction. The Java service should not decide SLA success or hold funds.
- Implement monitor submissions and the program's settlement instruction before replacing mock observations or settlement. The program determines the actual result and recipient. The frontend only requests settlement and displays program state.

For production, route `/api` and the frontend through the same origin. The Vite proxy covers local development only.
