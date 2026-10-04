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
- `src/services/uptime/uptimeService.ts` reads `GET /api/application/state` and `GET /api/uptime` from the Java backend (state from `uptime-service`, history from `uptime-monitor`). The Monitoring page renders a live five-minute, per-second timeline of the backend's own health, refreshed every 10 seconds. It does not score a customer's SLA.
- The Create SLA page collects separate customer service payment and provider guarantee amounts in SOL. `createSLA()` saves both in browser `localStorage`; no SOL is transferred. SLA uptime and monitor observations stay mocked until endpoint-specific monitoring and Solana integration exist.
- SLA escrow is two native SOL contributions. On success, the provider receives the customer's payment and its guarantee back. On breach, the customer receives its payment and the provider's forfeited guarantee. Keep each contribution explicit in the model and display their sum as total escrow. The Anchor integration must receive transfers authorized by both wallets before starting the SLA; represent on-chain amounts as lamports.
- `durationDays` accepts fractional values for short durations. `src/lib/agreementTerms.ts` owns the duration values and labels. New agreements can expire within 30 seconds; the pages show a second-by-second countdown. Monitoring cadence and HTTP timeout are service configuration, not SLA terms. The mock service makes ended agreements ready for a request, but records no payout or transaction for newly created agreements without monitor evidence.
- The current MVP uses one monitoring server. `consensusRequired=1` and `monitorCount=1` remain in mock models for compatibility, but the Create SLA and details pages do not expose consensus controls or panels.

## Same-origin development API

`vite.config.ts` proxies `/api/application` to the health provider `uptime-service` (`http://localhost:8080`, env `SLANA_UPTIME_SERVICE_TARGET`) and every other `/api` path to `uptime-monitor` (`http://localhost:8082`, env `SLANA_UPTIME_MONITOR_TARGET`). Set them before starting Vite to change the targets (for example, `http://host.docker.internal:8080`/`:8082` when Vite runs in the devcontainer and Java runs on the host). Browser calls use relative `/api` URLs, so the Java service needs no development CORS setting. Production hosting needs equivalent same-origin routing.

The frontend remains usable when the Java service is stopped: the Monitoring page shows `Backend unavailable` and a history error, while demo SLA data remains visible on the Dashboard and SLA details pages.

## Uptime deal (real Solana + backend integration)

The `/deal` page (`src/pages/UptimeDealPage.tsx`) is the one flow that uses no mocks. It works against the `uptime_deal` program (`../uptime-deal`) and `uptime-monitor` (a stateless proxy and the oracle: every 2 s it probes the provider and sends `record_observation` to every active on-chain deal that names its key; it has no database or deal registry, and it never settles; `uptime-service` is only the health provider the Start/Stop buttons control) as follows:

1. `GET /api/deals/config` returns the program id and the monitor's oracle key. It is the only `/api/deals` endpoint the frontend calls.
2. **Propose.** The payer's wallet signs `create_deal`. `src/services/deal/dealProgram.ts` builds the instruction by hand from the IDL layout (PDA `["deal", payer, deal_id]`, `deal_id` = `Date.now()`). Only the payment moves. `dealService.openDeal` sends it, polls `getSignatureStatuses` until it confirms (polling avoids the websocket that `confirmTransaction` needs) and returns the deal address. It does not call the monitor: the monitor finds the deal on chain by itself.
3. **Follow.** The page reads the deal account straight from chain every second (`readDeal`) and, once the account closes, the program's closing event (`readOutcome`). It shows the verdict line, the on-chain counters, an informational projection, the recipient (from the chain account) and its balance. The "Uptime monitor" panel shows the oracle and the provider state; "Simulate outage" / "Restore service" call `POST /api/application/{stop,start}`, so a manual test can force a refund.
4. **Accept.** When the deal has a provider guarantee and the connected wallet is the recipient, "Accept and lock guarantee" sends `accept_deal` (`dealService.acceptDeal`), which starts the window. The payer can "Cancel deal" (`cancel_deal`) until then.
5. **Settle.** The monitor does not settle. After the window plus the program's observation grace the page shows "Ready to settle on chain" and a "Settle now" button; any connected wallet can click it (`dealService.settleDeal`, the caller only pays the fee). The program compares its own counters with the threshold and pays the whole escrow to the provider or back to the payer.

Configuration comes from `src/config/solana.ts`:
- `VITE_SOLANA_RPC_URL` sets the cluster (default Devnet).
- `VITE_SOLANA_BURNER_WALLET=true` replaces Phantom/Solflare with `UnsafeBurnerWalletAdapter`. That adapter holds a throwaway in-browser key, so tests need no wallet extension. Each connect makes a new key; fund it with the page's "Airdrop 2 SOL" button (localnet/devnet faucet).
- `sameCluster(a, b)` compares RPC URLs (`localhost` = `127.0.0.1`, default ports, trailing slash ignored). The deal page compares `config.rpcUrl` from `/api/deals/config` with `SOLANA_RPC_URL`; on a mismatch it shows an error naming both URLs and `VITE_SOLANA_RPC_URL=<rpcUrl>`, and disables "Create deal". The devnet default is unchanged, so set `VITE_SOLANA_RPC_URL=http://127.0.0.1:8899` for a local service.
- The header pill shows Devnet, Localnet or Custom RPC.

To test by hand, run `../scripts/run-deal-demo.sh`. It starts a validator on :8899 with the program, `uptime-service` on :8080, `uptime-monitor` on :8082 and Vite on :5173 in burner mode. Then open `http://localhost:5173/deal` in two windows, one for the provider and one for the payer. Each window gets its own burner wallet; don't reload or disconnect either one during the demo.

`npm run test:e2e` (`playwright.config.ts`, `e2e/`) starts its own stack on separate ports (validator :18899, provider :18080 and monitor :18082 via `e2e/start-backend.sh`, which needs no database; Vite :5174). The monitor starts with an in-memory oracle key, the e2e validator RPC and a 2 s check interval. Two flows, each with a burner wallet:
- a 10-second deal that keeps the service up: once the page shows "Ready to settle on chain" the test clicks "Settle now" and asserts the recipient is paid the escrow on chain;
- one that simulates an outage: after "Settle now" the payer gets its payment back. It needs `anchor build` in `../uptime-deal`, Java and the Solana CLI. It is a cross-module test, so it is not part of `scripts/test-all.sh`; run it when you change the deal flow.
