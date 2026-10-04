# SLAna: minimum integration work

## Current frontend → service contract

The browser calls relative `/api` URLs on the Vite origin. `frontend/vite.config.ts` proxies `/api/application` to **`uptime-service`** (default `http://localhost:8080`, `SLANA_UPTIME_SERVICE_TARGET` overrides it) and the rest of `/api` to **`uptime-monitor`**. Both requests use `GET`, `Accept: application/json`, no request body, `cache: no-store`, and a 4-second client timeout. The Monitoring page sends them on mount, every 10 seconds, and when the user presses Refresh.

| Frontend request | Java endpoint and expected response | Frontend behavior |
| --- | --- | --- |
| `GET /api/application/state` | `uptime-service` returns `{ "status": "UP" }` or `{ "status": "DOWN" }`. | Shows the **Java service's own** current state. A failed request, non-2xx status, or unexpected shape shows `Backend unavailable`. |
| `GET /api/uptime?from=<ISO-8601>&to=<ISO-8601>` | `uptime-monitor` returns an array of `{ "time": "2026-10-03T12:00:00Z", "down": false }` entries, one per second in the inclusive range. `down: true` draws red; `false` draws green. | Requests 300 completed seconds ending two seconds before the browser's current second, groups them into up to 60 timeline blocks, and shows an error if the request or response is invalid. Missing backend records are reported as `down`. |

These are **read-only** requests. The frontend does not call `POST /api/application/start` or `/stop`. The Java service checks its own logical state; it does not check the HTTPS endpoint entered in Create SLA.

**SLA creation and settlement are currently mocked:** The methods in `frontend/src/services/solana/slaService.ts` use fixtures and browser `localStorage`; they do not call the Java uptime API or send Solana transactions. The UI collects an endpoint, wallets, a customer SOL payment, a provider SOL guarantee, uptime threshold, and duration. `consensusRequired=1` and `monitorCount=1` remain internal mock fields. When integrated, creation and settlement should use wallet-signed instructions to the Solana program; SLA outcomes and payouts must not be decided by the Java service.

### Create SLA data boundary

Submitting the Create SLA form calls `slaService.createSLA()` **inside the browser**. Nothing is sent to `uptime-service`, `uptime-monitor`, or Solana. The service receives this `CreateSLAInput` object and saves a new SLA in `localStorage`:

| Field | Value passed to the mock repository |
| --- | --- |
| `name` | Trimmed agreement name entered by the customer. |
| `endpoint` | Trimmed HTTPS URL entered by the customer. |
| `customerWallet` | Base58 public key of the connected Solana wallet. The form requires a wallet before creation. |
| `providerWallet` | Trimmed, validated Solana wallet address. |
| `customerPaymentSol` | Customer's service payment, validated from 0.000000001 to 10,000 SOL with up to nine decimals. |
| `providerGuaranteeSol` | Provider's SLA guarantee, validated from 0.000000001 to 10,000 SOL with up to nine decimals. |
| `requiredUptime` | Required percentage, greater than 0 and at most 100. |
| `durationDays` | Selected duration expressed as a fractional number of days; the current options range from 30 seconds to 30 days. |
| `consensusRequired`, `monitorCount` | Both fixed at `1` internally for the current single-server model; no form controls. |

The mock service assigns an ID and start/end timestamps, then returns the created SLA for navigation to its details page. The UI derives total escrow as `customerPaymentSol + providerGuaranteeSol`; it shows each contribution separately. On success, the provider receives the customer's payment and its own guarantee back. On breach, the customer receives its payment back plus the provider's forfeited guarantee. There is **no existing backend endpoint or request/response contract for creating an SLA**. When implementing the real flow, define the Anchor instruction and account data first; only add a Java target-registration request if the Java service will monitor that agreement's endpoint. The Java service must not accept responsibility for deciding settlement or holding escrow.

## Available now

- **Uptime deal, end to end (`/deal`):** a two-sided agreement on the real `uptime_deal` program.
  - The payer's wallet signs `create_deal`, which proposes the deal and locks the payment.
  - `POST /api/deals` registers the proposal with `uptime-monitor`, which is the program's oracle. It links the deal to a tracked service (by default the provider, `uptime-service`) and re-reads the proposal until it is accepted or cancelled.
  - The provider sees the proposal under "Proposals for you" and signs `accept_deal`. That locks its guarantee and starts the uptime window on chain; the program refuses the acceptance if the terms differ from the ones the provider was shown.
  - The monitor settles from the service's events. A failed check that makes more than 99% uptime unreachable closes the deal at once, ending tracking settles it with the uptime so far, and otherwise it settles when the window (for example 10 s) ends. The program pays both deposits to the provider when uptime is above 99%, and to the payer otherwise.
  - The page shows the verdict and the provider's on-chain balance. Covered by `frontend/e2e` (Playwright) and runnable by hand with `scripts/run-deal-demo.sh`, using two browser windows.
  - What it measures is the provider's health as checked by the monitor every 2 s, not a customer endpoint.
  - Tracked deals are stored in `monitor-db`, so they survive a monitor restart. If the monitor is down for good, either party can reclaim its deposit with `cancel_deal` ("Reclaim deposits" on the page) 10 minutes after the window ends. A proposal can be withdrawn by the payer or rejected by the provider at any time.

- The Monitoring page reads the Java service's own `GET /api/application/state` and `GET /api/uptime?from=...&to=...` endpoints. The timeline shows recorded `UP` or `DOWN` seconds for the **Java service**, refreshing every 10 seconds. It does not measure customer API endpoints.
- Vite proxies `/api/application` requests to `uptime-service` during development (default `http://localhost:8080`, or `SLANA_UPTIME_SERVICE_TARGET`), and the rest of `/api` to `uptime-monitor`, so the browser does not need CORS.
- The Create SLA page calls `frontend/src/services/solana/slaService.ts#createSLA`. It stores the customer payment and provider guarantee in browser `localStorage`; it does not call a backend, create a Solana account, or transfer SOL.
- Demo terms can expire in 30 seconds or a few minutes. The post-expiry action records a mock settlement request for new agreements, with no recipient or transaction because customer endpoint observations are unavailable.

## Frontend handoff: what is still simulated

- Wallet Adapter connects to Solana Devnet, but Create SLA and settlement do not submit wallet-signed instructions.
- Dashboard and SLA details use three seed agreements and browser `localStorage` through `frontend/src/services/solana/slaService.ts`. Their customer endpoint uptime, statuses, recent observations, and transaction hashes are fixtures in `frontend/src/mocks/data.ts`. The Monitoring page's Java-service timeline is the only live uptime history.
- The local mock storage key was versioned for the two-contribution SOL schema. Older browser-only agreements use an incompatible model and are not loaded.
- `consensusRequired=1` and `monitorCount=1` remain internal mock fields for model compatibility. They are not agreement form settings.
- `settleSLA()` records a local mock result. No SOL is transferred, and new agreements have no evidence-based payout decision. Keep SLA pass/fail and fund-recipient decisions out of React when replacing this code.
- Existing Vitest page assertions still refer to older form controls, monitor panels, and copy; update them when implementing the real integrations. The current TypeScript/Vite build passes, but those assertions have not been updated as part of the UI simplification.

## Minimum missing API for customer endpoint uptime

1. Add a way to register the endpoint from an agreement: `POST /api/monitoring/targets` with an SLA/program account ID and HTTPS endpoint. Return a stable target ID. The backend owns the check schedule and request timeout as service configuration, then persists timestamped results. Outages between checks may be missed. The current sampler checks only the Java service itself.
2. Add `GET /api/monitoring/targets/{targetId}/uptime?from=...&to=...` returning timestamped `UP`/`DOWN` samples (or the existing `{time, down}` shape). This lets the frontend show a timeline for the SLA's endpoint. Keep the Java service's own `/api/uptime` separate.

These endpoints provide a basic single-server uptime view. Independent signed monitors, consensus, and verifiable observations are later work; the current Java service cannot supply them.

## Solana integration, separate from the Java API

- Implement the Anchor create/escrow instruction and account reads, then replace the mock `createSLA` and read methods in `slaService.ts`. The wallet must sign the transaction. The Java service should not decide SLA success or hold funds.
- Escrow two native SOL contributions: the customer's service payment and the provider's SLA guarantee. Both parties must authorize their own transfer before the SLA starts. Store amounts as integer lamports (`1 SOL = 1,000,000,000 lamports`) and use System Program transfers; no SPL mint or token account is needed. Return the combined amount to the provider on success or the customer on breach. Monitoring cadence and HTTP timeout belong to uptime-service configuration, not SLA terms.
- Implement monitor submissions and the program's settlement instruction before replacing mock observations or settlement. The program determines the actual result and recipient. The frontend only requests settlement and displays program state.

For production, route `/api` and the frontend through the same origin. The Vite proxy covers local development only.
