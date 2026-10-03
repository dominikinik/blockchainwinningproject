# SLAna: minimum integration work

## Current frontend → service contract

The browser calls relative `/api` URLs on the Vite origin. `frontend/vite.config.ts` proxies them to **`uptime-service`**, default `http://localhost:8080` (`SLANA_UPTIME_SERVICE_TARGET` overrides it). The frontend never calls `uptime-db` directly. Both requests use `GET`, `Accept: application/json`, no request body, `cache: no-store`, and a 4-second client timeout. The Monitoring page sends them on mount, every 10 seconds, and when the user presses Refresh.

| Frontend request | Java endpoint and expected response | Frontend behavior |
| --- | --- | --- |
| `GET /api/application/state` | `uptime-service` returns `{ "status": "UP" }` or `{ "status": "DOWN" }`. | Shows the **Java service's own** current state. A failed request, non-2xx status, or unexpected shape shows `Backend unavailable`. |
| `GET /api/uptime?from=<ISO-8601>&to=<ISO-8601>` | `uptime-service` returns an array of `{ "time": "2026-10-03T12:00:00Z", "down": false }` entries, one per second in the inclusive range. `down: true` draws red; `false` draws green. | Requests 300 completed seconds ending two seconds before the browser's current second, groups them into up to 60 timeline blocks, and shows an error if the request or response is invalid. Missing backend records are reported as `down` by the Java service. |

These are **read-only** requests. The frontend does not call `POST /api/application/start` or `/stop`. The Java service checks its own logical state; it does not check the HTTPS endpoint entered in Create SLA.

**No backend request for SLA creation or settlement:** All methods in `frontend/src/services/solana/slaService.ts` use fixtures and browser `localStorage`, including `createSLA()`, SLA/monitor/observation reads, and `settleSLA()`. Wallet Adapter is configured for Solana Devnet, but these methods do not send a Solana transaction. The current UI collects an endpoint, wallets, escrow amount, uptime threshold, duration, and check interval; `timeoutMs=2000`, `consensusRequired=1`, and `monitorCount=1` remain internal mock fields.

### Create SLA data boundary

Submitting the Create SLA form calls `slaService.createSLA()` **inside the browser**. Nothing is sent to `uptime-service`, `uptime-db`, or Solana. The service receives this `CreateSLAInput` object and saves a new SLA in `localStorage`:

| Field | Value passed by the frontend |
| --- | --- |
| `name` | Trimmed agreement name entered by the customer. |
| `endpoint` | Trimmed HTTPS URL entered by the customer. |
| `customerWallet` | Base58 public key of the connected Solana wallet. The form requires a wallet before creation. |
| `providerWallet` | Trimmed, validated Solana wallet address. |
| `escrowSol` | Number of SOL entered, validated from 0.001 to 10,000 with up to three decimal places. |
| `requiredUptime` | Required percentage, greater than 0 and at most 100. |
| `durationDays` | Selected duration expressed as a fractional number of days; the current options range from 30 seconds to 30 days. |
| `checkIntervalMinutes` | Selected interval expressed as a fractional number of minutes: 10 seconds, 1 minute, 5 minutes, or 10 minutes. It cannot exceed the duration. |
| `timeoutMs` | Fixed at `2000` internally; no form control. |
| `consensusRequired`, `monitorCount` | Both fixed at `1` internally for the current single-server model; no form controls. |

The mock service assigns an ID and start/end timestamps, then returns the created SLA for navigation to its details page. There is **no existing backend endpoint or request/response contract for creating an SLA**. When implementing the real flow, define the Anchor instruction and account data first; only add a Java target-registration request if the Java service will monitor that agreement's endpoint. The Java service must not accept responsibility for deciding settlement or holding escrow.

## Available now

- **Uptime deal, end to end (`/deal`):** the wallet signs the real `uptime_deal` `create_deal` instruction, which locks SOL and fixes the uptime window on chain. `POST /api/deals` registers the deal with `uptime-service`, which is the program's oracle. After the window (for example 10 s), the service sends `settle_deal` with its own recorded up/total seconds. The program pays the recipient when uptime is above 99% and refunds the payer otherwise. The page shows the verdict and the recipient's on-chain balance. Covered by `frontend/e2e` (Playwright) and runnable by hand with `scripts/run-deal-demo.sh`. What it measures is the Java service's own health, not a customer endpoint. Tracked deals live in the service's memory, so a restart before settlement stops it from settling them; the payer can then reclaim the escrow with `cancel_deal` ("Reclaim escrow" on the page) 10 minutes after the window ends.

- The Monitoring page reads the Java service's own `GET /api/application/state` and `GET /api/uptime?from=...&to=...` endpoints. The timeline shows recorded `UP` or `DOWN` seconds for the **Java service**, refreshing every 10 seconds. It does not measure customer API endpoints.
- Vite proxies relative `/api` requests to `uptime-service` during development, so the browser does not need CORS. The target is `http://localhost:8080` by default, or `SLANA_UPTIME_SERVICE_TARGET`.
- The Create SLA page calls `frontend/src/services/solana/slaService.ts#createSLA`. That method only saves a demo agreement in browser `localStorage`. It does not call a backend, create a Solana account, or lock SOL.
- Demo terms can expire in 30 seconds or a few minutes. The post-expiry action records a mock settlement request for new agreements, with no recipient or transaction because customer endpoint observations are unavailable.

## Frontend handoff: what is still simulated

- Wallet Adapter connects to Solana Devnet, but Create SLA and settlement do not submit wallet-signed instructions.
- Dashboard and SLA details use three seed agreements and browser `localStorage` through `frontend/src/services/solana/slaService.ts`. Their customer endpoint uptime, statuses, recent observations, and transaction hashes are fixtures in `frontend/src/mocks/data.ts`. The Monitoring page's Java-service timeline is the only live uptime history.
- The create form retains `timeoutMs=2000`, `consensusRequired=1`, and `monitorCount=1` in its payload for model compatibility. Request timeout and consensus are not configurable in the current one-server UI.
- `settleSLA()` records a local mock result. No escrow is locked, no SOL is transferred, and new agreements have no evidence-based payout decision. Keep SLA pass/fail and fund-recipient decisions out of React when replacing this code.
- Existing Vitest page assertions still refer to older form controls, monitor panels, and copy; update them when implementing the real integrations. The current TypeScript/Vite build passes, but those assertions have not been updated as part of the UI simplification.

## Minimum missing API for customer endpoint uptime

1. Add a way to register the endpoint and schedule from an agreement: `POST /api/monitoring/targets` with an SLA/program account ID, HTTPS endpoint, check interval, and timeout. Return a stable target ID. The backend must send an HTTP request at each scheduled interval and persist its timestamped result. An interval is the gap between checks; outages entirely between checks may be missed. The current sampler checks only the Java service itself.
2. Add `GET /api/monitoring/targets/{targetId}/uptime?from=...&to=...` returning timestamped `UP`/`DOWN` samples (or the existing `{time, down}` shape). This lets the frontend show a timeline for the SLA's endpoint. Keep the Java service's own `/api/uptime` separate.

These endpoints provide a basic single-server uptime view. Independent signed monitors, consensus, and verifiable observations are later work; the current Java service cannot supply them.

## Solana integration, separate from the Java API

- Implement the Anchor create/escrow instruction and account reads, then replace the mock `createSLA` and read methods in `slaService.ts`. The wallet must sign the transaction. The Java service should not decide SLA success or hold funds.
- Implement monitor submissions and the program's settlement instruction before replacing mock observations or settlement. The program determines the actual result and recipient. The frontend only requests settlement and displays program state.

For production, route `/api` and the frontend through the same origin. The Vite proxy covers local development only.
