# CLAUDE.md — sla-monitor

A monitor node for the `sla` program (TypeScript, Node 24). Each node runs with its own keypair, which an admin registers on-chain. The node finds the SLAs that list it, checks their endpoints on schedule, submits one signed `submit_report` per window, and cranks `finalize_window`. Keep this file in step with changes to the module's behavior, architecture, configuration, or commands.

## Status

Implemented (T2). The node runs against the IDL and SPEC; the on-chain handlers are T1's, so end-to-end runs need the program built.

## Configuration

CLI flags override env vars (`.env` is loaded by `npm start` if present; see `.env.example`).

| Env var | Flag | Default | Meaning |
|---|---|---|---|
| `KEYPAIR_PATH` | `--keypair` | required | monitor wallet keypair JSON (registered on-chain) |
| `RPC_URL` | `--rpc-url` | `http://localhost:8899` | http(s) RPC endpoint |
| `PROGRAM_ID` | `--program-id` | address in `src/idl/sla.json` | `sla` program |
| `POLL_INTERVAL_SECS` | `--poll-interval` | `10` | period of the refresh/report/finalize loop (positive integer) |
| `STATE_FILE` | `--state-file` | `./monitor-state.json` | unsent-report state (atomic JSON) |

## Architecture

Pure modules plus an injected-dependency engine. Only `client.ts` and `main.ts` touch Anchor, the network, or the filesystem layout.

- `schedule.ts`: window/slot math from SPEC (`end_i = min(start_i + W, end_ts)`, `slots_i = ceil(len / I)`, current slot).
- `bitmap.ts`: 32-byte LSB-first bitmaps, hex (de)serialisation.
- `checker.ts`: one HTTP check with an `AbortController` timeout. UP = 2xx in time; timeout or network error = checked but down.
- `retry.ts`: bounded exponential backoff for RPC errors; program errors (6000+) are not retried. `programErrorCode` extracts the code.
- `state.ts`: `StateStore` (`MemoryStateStore`, `FileStateStore` writes temp file + rename on every change). One `PendingReport` per (SLA, window).
- `client.ts`: `ProgramClient` interface (`fetchAssignedSlas`, `fetchWindowReportPayer`, `submitReport`, `finalizeWindow`) and the thin `AnchorProgramClient`. `fetchAssignedSlas` runs 5 memcmp queries at `92 + 32*i` and dedupes. `finalizeWindow` passes every `sla.monitors` Monitor PDA as writable remaining accounts.
- `monitor.ts`: `Monitor` engine with injected `client`, `store`, `fetch`, `now()` (Unix seconds), `sleep`:
  - `refresh()` reloads assigned SLAs (keeps the old list on failure).
  - `runChecks()` checks the slot due now (once per slot; missed slots stay unchecked, no catch-up). Never checks before start, at/after `end_ts`, or settled SLAs. Updates the stored bitmaps after each check.
  - `runReports()` submits entries with `end_i <= now < end_i + G`, then deletes them. DuplicateReport (6021) counts as success, ReportDeadlinePassed (6020) drops, other errors keep the entry for the next run. Entries past the deadline are dropped. Windows with no checks are not reported.
  - `runFinalize()` for each unsettled assigned SLA finalizes `next_window_to_finalize` onward (up to 10 per run, in order, stopping at the first failure) once `now >= end_i + G`; payer is `WindowReport.payer` if the account exists, else this wallet.
- `main.ts`: wires real dependencies. Checks run on a 1 s interval (in-flight de-duplicated); refresh, reports and finalize run in a loop every `POLL_INTERVAL_SECS`.

Limit: a window's report is only sent from a node that was running during or after the window and before its deadline; at most `G` seconds after `end_i`, so keep the poll interval well below `report_grace_secs`.

## Contract

- `src/idl/sla.json` is the program IDL, copied by `../scripts/sync-idl.sh` from `sla-program/target/idl/`. Never edit it here. Re-run the script after the program changes.
- Behavior the IDL doesn't express is in [`../sla-program/SPEC.md`](../sla-program/SPEC.md):
  - window and slot times
  - bitmap bit order
  - the reporting deadline
  - memcmp offsets for finding SLAs by monitor
  - which accounts each instruction needs, including remaining accounts

## Commands

Run from `sla-monitor/`:

```bash
npm install
npm test                         # all tests once (Vitest)
npm run test:watch
npx vitest run src/monitor.test.ts   # single file
npx vitest run -t "exposes"      # single test by name
npm run typecheck                # tsc --noEmit, including tests
npm run build                    # tsc to dist/ (excludes tests; dist does not copy src/idl/sla.json)
npm start                        # run the node via tsx (needs KEYPAIR_PATH)
```

## Testing notes

Tests use Vitest with a fake clock (`vi.useFakeTimers`), a mocked `fetch`, and a mocked program client. They never touch the network or a validator, and must stay fast and deterministic.
