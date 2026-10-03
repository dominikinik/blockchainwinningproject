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
npm run test:e2e                                # Playwright, real stack (~45s; see "Uptime deal" below)
```

## Tests

- Vitest + jsdom + Testing Library, configured in the `test` block of `vite.config.ts`; setup in `src/test/setup.ts` (jest-dom matchers, cleanup, localStorage reset, TZ=UTC, silences React Router future-flag warnings). Shared helpers (`renderAt`, `makeSLA`) are in `src/test/utils.tsx`.
- Tests sit next to the code as `*.test.ts(x)` and are typechecked by `npm run build`.
- Page tests mock `slaService`, `uptimeService` and the wallet-adapter modules with `vi.mock`; they never touch the network. `src/App.tsx` holds the route tree (without Router/wallet providers, which stay in `main.tsx`) so it can be rendered under `MemoryRouter`.
- `slaService` and `uptimeService` tests use fake timers (`vi.advanceTimersByTimeAsync`) for the mock delays and the 4s fetch timeout; never wait on real timers.
- Every new feature or behavior change must come with tests, and the suite must stay fast and deterministic.
- `src/services/deal/dealProgram.test.ts` and `dealService.test.ts` run under `// @vitest-environment node`: web3.js PDA hashing rejects jsdom's cross-realm `Uint8Array`. `src/test/setup.ts` therefore guards its `window` access.

## Data flow

- `src/services/solana/slaService.ts` is the SLA data boundary. Its agreement, monitor, observation, and settlement methods currently use fixtures in `src/mocks/data.ts` and browser `localStorage`.
- React must not determine SLA success, consensus, or actual settlement. When the Anchor program is available, replace the mock service methods with wallet-signed instructions and program account reads.
- `src/services/uptime/uptimeService.ts` reads `GET /api/application/state` and `GET /api/uptime` from the Java backend. The Monitoring page renders a live five-minute, per-second timeline of the backend's own health, refreshed every 10 seconds. It does not score a customer's SLA.
- The Create SLA page remains available. `createSLA()` saves a demo agreement in browser `localStorage`; no SOL is locked. SLA uptime and monitor observations stay mocked until endpoint-specific monitoring and Solana integration exist.
- `durationDays` and `checkIntervalMinutes` also accept fractional values for seconds/minutes in demo terms. `src/lib/agreementTerms.ts` owns the selectable values and labels. New demo agreements can expire within 30 seconds; the pages show a second-by-second countdown. The mock service makes ended agreements ready for a request, but records no payout or transaction for newly created agreements without monitor evidence.
- The Create SLA form always shows 10-second, 1-minute, 5-minute, and 10-minute intervals. It warns and rejects submission when the selected interval exceeds the agreement duration. This is form validation only; it does not perform uptime checks.
- Request timeout remains a fixed 2,000 ms field in the mock SLA model and create payload; the form and details page do not expose it while customer endpoint monitoring is absent.
- The current MVP uses one monitoring server. `consensusRequired=1` and `monitorCount=1` remain in mock models for compatibility, but the Create SLA and details pages do not expose consensus controls or panels.

## Same-origin development API

`vite.config.ts` proxies `/api` to `http://localhost:8080` by default. Set `SLANA_UPTIME_SERVICE_TARGET` before starting Vite to change the target (for example, `http://host.docker.internal:8080` when Vite runs in the devcontainer and Java runs on the host). Browser calls use relative `/api` URLs, so the Java service needs no development CORS setting. Production hosting needs equivalent same-origin routing.

The frontend remains usable when the Java service is stopped: the Monitoring page shows `Backend unavailable` and a history error, while demo SLA data remains visible on the Dashboard and SLA details pages.

## Uptime deal (real Solana + backend integration)

The `/deal` page (`src/pages/UptimeDealPage.tsx`) is the one flow that uses no mocks. It works against the `uptime_deal` program (`../uptime-deal`) and `uptime-service` as follows:

1. `GET /api/deals/config` returns the program id and the service's oracle key.
2. The connected wallet signs `create_deal`. `src/services/deal/dealProgram.ts` builds the instruction by hand from the IDL layout: discriminator, u64 LE `deal_id` (= `Date.now()`), amount and `duration_seconds` (1..86,400; 32 bytes), with the PDA `["deal", payer, deal_id]`. `dealService.openDeal` sends it and polls `getSignatureStatuses` until it confirms. Polling avoids the websocket that `confirmTransaction` needs.
3. `POST /api/deals {address}` registers the deal; the window (`starts_at` + duration) comes from the chain. If registration fails after `create_deal` confirmed, `openDeal` throws an error naming the deal address and the `cancel_deal` fallback. The service measures its own per-second uptime over the window and sends `settle_deal` as the oracle.
4. The page polls `GET /api/deals/{address}` every second. It shows a countdown, the measured up/total seconds, the program's verdict ("Paid to recipient" / "Refunded to payer", read from the `DealSettled` event) and the recipient's on-chain balance. "Simulate outage" / "Restore service" call `POST /api/application/{stop,start}`, so a manual test can force a refund.
5. "Reclaim escrow": when the deal is FAILED, or ACTIVE past `endsAt` + 600 s, and the connected wallet is the payer, the page shows a button that sends `cancel_deal` (`dealService.cancelDeal`). It is enabled once `endsAt` + 600 s (`CANCEL_TIMEOUT_SECONDS`) has passed, and on success the deal shows as CANCELLED ("Cancelled · escrow returned to payer"). The window input stays at 1..3600 (the backend's default maximum).

Configuration comes from `src/config/solana.ts`:
- `VITE_SOLANA_RPC_URL` sets the cluster (default Devnet).
- `VITE_SOLANA_BURNER_WALLET=true` replaces Phantom/Solflare with `UnsafeBurnerWalletAdapter`. That adapter holds a throwaway in-browser key, so tests need no wallet extension. Each connect makes a new key; fund it with the page's "Airdrop 2 SOL" button (localnet/devnet faucet).
- `sameCluster(a, b)` compares RPC URLs (`localhost` = `127.0.0.1`, default ports, trailing slash ignored). The deal page compares `config.rpcUrl` from `/api/deals/config` with `SOLANA_RPC_URL`; on a mismatch it shows an error naming both URLs and `VITE_SOLANA_RPC_URL=<rpcUrl>`, and disables "Create deal". The devnet default is unchanged, so set `VITE_SOLANA_RPC_URL=http://127.0.0.1:8899` for a local service.
- The header pill shows Devnet, Localnet or Custom RPC.

To test by hand, run `../scripts/run-deal-demo.sh`. It starts a validator on :8899 with the program, the service on :8080 and Vite on :5173 in burner mode. Then open `http://localhost:5173/deal`.

`npm run test:e2e` (`playwright.config.ts`, `e2e/`) starts its own stack on separate ports (validator :18899, service :18080 on the `uptime_test` database via `e2e/start-backend.sh`, Vite :5174). It runs two 10-second deals: one keeps the service up and asserts the recipient received exactly the escrow on chain; the other simulates an outage and asserts the refund. It needs `anchor build` in `../uptime-deal`, Docker, Java and the Solana CLI. It is a cross-module test, so it is not part of `scripts/test-all.sh`; run it when you change the deal flow. The scripts reuse a running `uptime-db` container, because `docker compose up` from another worktree would recreate it on that worktree's `data/` directory.
