# SLAna integration gaps

## Connected now

- The Monitoring page reads `GET /api/application/state` from `uptime-service`. It displays the backend's own logical `UP` or `DOWN` state, or `Unavailable` when the service cannot be reached.
- In development, Vite proxies relative `/api` requests to the Java service. The browser calls the Vite origin, so no browser CORS configuration is needed. The target defaults to `http://localhost:8080` and can be changed with `SLANA_UPTIME_SERVICE_TARGET`.

## Still mocked

- `GET /api/uptime` and `/api/uptime/at` are **not** used for SLA scoring. They describe the Java service's own per-second health, not a customer's API endpoint.
- SLA accounts, providers, escrow, registered monitors, signed observations, consensus, uptime history, projections, and mock settlement results come from `frontend/src/mocks/data.ts` and browser `localStorage` through `frontend/src/services/solana/slaService.ts`.
- The frontend does not currently lock or transfer SOL. Observation transaction signatures are placeholders.

## Needed for an end-to-end SLA

1. **Agreement and monitor data contract:** define stable IDs and read APIs or program accounts for each customer API, monitor identity, observation, and agreement period. The current Java service has no per-endpoint or per-SLA monitoring endpoint.
2. **Independent monitoring:** add signed checks for customer endpoints, including check interval, timeout, timestamps, monitor wallet, latency, and transaction signature. The existing service samples only its own logical health.
3. **Solana program integration:** implement account reads and wallet-signed create/escrow/settle instructions. The program must determine consensus, SLA status, and actual fund recipient. Replace the mock methods in `slaService.ts` without moving those decisions into React.
4. **Production routing:** serve `/api` and the frontend from the same origin through a reverse proxy or gateway. The Vite proxy covers local development only; a deployment needs equivalent routing.
5. **Runtime setup:** `uptime-service` needs Java 21 and PostgreSQL from `uptime-db`. The current Solana devcontainer has no JDK, so run the Java service on the host or extend the container setup. For a frontend running inside the devcontainer while Java runs on the host, set `SLANA_UPTIME_SERVICE_TARGET=http://host.docker.internal:8080`.
6. **Control endpoint protection:** `POST /api/application/stop` and `/start` currently switch the service's reported state without authentication. They are intentionally not called by the frontend; protect them before exposing the backend publicly.
