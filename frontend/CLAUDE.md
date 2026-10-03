# CLAUDE.md — frontend

This module is the SLAna React/TypeScript UI. Keep this file in step with changes to its behavior, architecture, configuration, or commands.

## Commands

Run from `frontend/`:

```bash
npm install
npm run dev       # Vite on port 5173
npm run build     # TypeScript check and production build
npm run preview
npm test                                        # all tests once (Vitest, jsdom; ~3s)
npm run test:watch                              # watch mode
npx vitest run src/lib/format.test.ts           # single file
npx vitest run -t "settles an ended SLA"        # single test by name
```

## Tests

- Vitest + jsdom + Testing Library, configured in the `test` block of `vite.config.ts`; setup in `src/test/setup.ts` (jest-dom matchers, cleanup, localStorage reset, TZ=UTC, silences React Router future-flag warnings). Shared helpers (`renderAt`, `makeSLA`) are in `src/test/utils.tsx`.
- Tests sit next to the code as `*.test.ts(x)` and are typechecked by `npm run build`.
- Page tests mock `slaService`, `uptimeService` and the wallet-adapter modules with `vi.mock`; they never touch the network. `src/App.tsx` holds the route tree (without Router/wallet providers, which stay in `main.tsx`) so it can be rendered under `MemoryRouter`.
- `slaService` and `uptimeService` tests use fake timers (`vi.advanceTimersByTimeAsync`) for the mock delays and the 4s fetch timeout; never wait on real timers.
- Every new feature or behavior change must come with tests, and the suite must stay fast and deterministic.

## Data flow

- `src/services/solana/slaService.ts` is the SLA data boundary. It keeps the demo data path for the browser but now applies the canonical escrow settlement rule used by the planned on-chain program: if measured availability is below `99.0%`, the customer receives a `30%` refund; otherwise the provider receives the full escrow value.
- React must not determine SLA success, consensus, or actual settlement. When the Anchor program is available, replace the mock service methods with wallet-signed instructions and program account reads, but keep the same settlement policy and state-machine semantics.
- `src/services/uptime/uptimeService.ts` reads `GET /api/application/state` and `GET /api/uptime` from the Java backend. The Monitoring page renders a live five-minute, per-second timeline of the backend's own health, refreshed every 10 seconds. It does not score a customer's SLA.
- The Create SLA page remains available. `createSLA()` saves a demo agreement in browser `localStorage`; no SOL is locked. SLA uptime and monitor observations stay mocked until endpoint-specific monitoring and Solana integration exist.
- `durationDays` and `checkIntervalMinutes` also accept fractional values for seconds/minutes in demo terms. `src/lib/agreementTerms.ts` owns the selectable values and labels. New demo agreements can expire within 30 seconds; the pages show a second-by-second countdown. The mock service makes ended agreements ready for a request, but records no payout or transaction for newly created agreements without monitor evidence.
- The Create SLA form always shows 10-second, 1-minute, 5-minute, and 10-minute intervals. It warns and rejects submission when the selected interval exceeds the agreement duration. This is form validation only; it does not perform uptime checks.
- Request timeout remains a fixed 2,000 ms field in the mock SLA model and create payload; the form and details page do not expose it while customer endpoint monitoring is absent.
- The current MVP uses one monitoring server. `consensusRequired=1` and `monitorCount=1` remain in mock models for compatibility, but the Create SLA and details pages do not expose consensus controls or panels.

## Same-origin development API

`vite.config.ts` proxies `/api` to `http://localhost:8080` by default. Set `SLANA_UPTIME_SERVICE_TARGET` before starting Vite to change the target (for example, `http://host.docker.internal:8080` when Vite runs in the devcontainer and Java runs on the host). Browser calls use relative `/api` URLs, so the Java service needs no development CORS setting. Production hosting needs equivalent same-origin routing.

The frontend remains usable when the Java service is stopped: the Monitoring page shows `Backend unavailable` and a history error, while demo SLA data remains visible on the Dashboard and SLA details pages.
