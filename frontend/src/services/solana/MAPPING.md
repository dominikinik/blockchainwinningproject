# Chain → UI mapping

This file defines how `sla` program accounts (see [`sla-program/SPEC.md`](../../../../sla-program/SPEC.md)) map to the UI types in [`src/types/index.ts`](../../types/index.ts). The UI types stay unchanged, so pages don't need to know whether data comes from the chain or from mocks. `idl/sla.json` is copied here by `scripts/sync-idl.sh`. Don't edit it by hand.

Notation: `S` is a decoded `Sla` account, `now` is the current Unix time in seconds, and `G = S.report_grace_secs`. Lamports convert to SOL as `lamports / 1e9`.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `VITE_SLA_DATA_SOURCE` | `mock` | `mock` keeps the current fixtures and `localStorage` source; `chain` reads from and sends to the program. Tests and demos run on `mock`. |
| `VITE_SOLANA_RPC_URL` | `http://localhost:8899` | RPC endpoint (Surfpool or `solana-test-validator` locally, devnet for demos). |
| `VITE_SLA_PROGRAM_ID` | `4ACuzhWwVVqtbYgicWzq11BtowhsEEHLcChJn2gVR4R9` | The program ID. |

## `Sla` account → `SLA`

| `SLA` field | Source |
|---|---|
| `id` | The `Sla` PDA, base58 |
| `name`, `endpoint` | `S.name`, `S.endpoint` |
| `customerWallet`, `providerWallet` | `S.customer`, `S.provider`, base58 |
| `escrowSol` | `S.escrow_lamports / 1e9` |
| `requiredUptime` | `S.required_uptime_bps / 100` (9990 → 99.9) |
| `currentUptime` | `S.counted_checks > 0 ? S.up_checks / S.counted_checks * 100 : 0` |
| `successfulChecks` | `S.up_checks` |
| `failedChecks` | `S.counted_checks − S.up_checks` |
| `startAt`, `endAt` | `S.start_ts`, `S.end_ts` as ISO strings |
| `durationDays` | `(S.end_ts − S.start_ts) / 86400` |
| `checkIntervalMinutes` | `S.check_interval_secs / 60` |
| `timeoutMs` | `S.timeout_ms` |
| `consensusRequired` | `S.consensus_required` |
| `monitorCount` | `S.monitors.length` |
| `status` | See [Status](#status) |
| `history` | See [History and timeline](#history-and-timeline) |
| `timeline` | See [History and timeline](#history-and-timeline) |
| `settlement` | See [Settlement](#settlement) |

### Status

Apply these rules in order. The first match wins.

1. `completed`: `S.settled`.
2. `pending`: `S.next_window_to_finalize == 0` (no window is finalized yet).
3. `violated`: the target can't be met even if every remaining check succeeds. Let `R` be the number of check slots in windows `next_window_to_finalize .. total_windows` (slot counts per SPEC.md "Time, windows and check slots"). The status is violated when `(up_checks + R) · 10000 < required_uptime_bps · (counted_checks + R)`. When `R = 0` and `counted_checks = 0`, D4 refunds the customer, so this also counts as `violated`.
4. `at-risk`: `counted_checks > 0` and `up_checks · 10000 < required_uptime_bps · counted_checks`.
5. `healthy`: none of the above.

Use integer (or `BigInt`) arithmetic for these comparisons, as the program does, so the UI and the program agree at the exact threshold.

### History and timeline

Both come from `S.window_results`. Entry `i` is window `i`, which starts at `start_ts + i · window_secs`.

- **`history: UptimeBucket[]`**:
  - If `total_windows ≤ 24`, use one bucket per finalized window, labelled with its UTC start time `HH:mm`.
  - Otherwise, use one bucket per UTC calendar day of window start, labelled with the short weekday (`Mon`), and keep the last 7.
  - For each bucket: `checks = Σ counted`, `failed = Σ (counted − up)`, and `uptime = checks > 0 ? Σup / checks · 100 : 100`.
- **`timeline: ObservationResult[]`**: the last 48 finalized windows with `counted > 0`, in order.
  - `'up'` if the window meets the target (`up · 10000 ≥ required_uptime_bps · counted`); otherwise `'down'`.
  - Windows with no counted checks are omitted.

### Settlement

| `Settlement` field | Source |
|---|---|
| `state` | `'settled'` if `S.settled`. Otherwise `'ready'` if `now ≥ S.end_ts + G` and `S.next_window_to_finalize == S.total_windows`. Otherwise `'pending'`. |
| `projectionRecipient` | D4 applied to current totals: `'provider'` if `counted_checks > 0` and `up_checks · 10000 ≥ required_uptime_bps · counted_checks`, else `'customer'` |
| `projectionAmountSol` | `escrowSol` |
| `actualRecipient` | `S.recipient` lowercased (`Provider` → `'provider'`), if set |
| `transaction`, `settledAt` | Signature and block time of the transaction that emitted `SlaSettled` for this SLA (from `getSignaturesForAddress(sla)`), if found |

## `Monitor` account → `Monitor`

| `Monitor` field | Source |
|---|---|
| `id` | The `Monitor` PDA, base58 |
| `name` | `name` |
| `wallet` | `authority`, base58 |
| `status` | `active ? 'online' : 'offline'` |
| `observations` | `reports_submitted` |
| `agreementRate` | `slots_voted > 0 ? slots_agreed / slots_voted · 100 : 100` (a percentage, like the mocks' `99.8`) |
| `lastObservationAt` | `timestamp` of the monitor's latest `ReportSubmitted` event, as an ISO string, or `''` if there is none |

## `ReportSubmitted` events → `Observation[]`

Each `ReportSubmitted` event becomes one `Observation` per set `checked` bit `j`:

| `Observation` field | Source |
|---|---|
| `id` | `` `${signature}:${j}` `` |
| `slaId` | `event.sla` |
| `monitorId` | The `Monitor` PDA of `event.monitor` |
| `timestamp` | `start_ts + window_index · window_secs + j · check_interval_secs`, as an ISO string |
| `result` | `up` bit `j` set ? `'up'` : `'down'` |
| `latencyMs` | `null`. The chain has no latency; the Java live timeline (T5) shows it. |
| `transaction` | The signature of the transaction that emitted the event |

The bit layout is in SPEC.md ("Bitmaps").

## `CreateSLAInput` → `create_sla`

| Argument | Source |
|---|---|
| `sla_id` | 16 random bytes (`crypto.getRandomValues`) |
| `params.provider` | `providerWallet` |
| `params.name`, `params.endpoint` | `name`, `endpoint` |
| `params.escrow_lamports` | `Math.round(escrowSol · 1e9)` |
| `params.required_uptime_bps` | `Math.round(requiredUptime · 100)` |
| `params.duration_secs` | `Math.round(durationDays · 86400)` |
| `params.check_interval_secs` | `Math.round(checkIntervalMinutes · 60)` |
| `params.timeout_ms` | `timeoutMs` |
| `params.consensus_required` | `consensusRequired` |
| `monitors` | `monitorCount` active registered monitors (D8). The `Monitor` PDAs go in remaining accounts, in the same order. |

`customerWallet` must be the connected wallet, which signs and pays. In `chain` mode, the form must also enforce the program's validation rules (SPEC.md, `create_sla`). For example, the endpoint must be `https://` and the timeout must be shorter than the check interval.
