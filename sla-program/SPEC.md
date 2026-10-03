# `sla` program contract

This is the frozen interface between `sla-program` and its clients (`sla-monitor`, `frontend`). The generated IDL (`target/idl/sla.json`, copied out by `scripts/sync-idl.sh`) is the machine-readable form. This file defines the behavior the IDL can't express. Changing an account layout, an instruction signature, an event, or the error order is a contract change: update this file, rebuild, re-sync the IDL, and update every client in the same change.

- **Program ID (local and devnet):** `4ACuzhWwVVqtbYgicWzq11BtowhsEEHLcChJn2gVR4R9`. The deploy keypair is `target/deploy/sla-keypair.json`. It is gitignored and not shared, so on a new clone, either copy it in from the original machine or run `anchor keys sync` (which generates a new ID) and update this line, `Anchor.toml`, `declare_id!`, and the clients' env.
- **Clusters (D7):** Surfpool or `solana-test-validator` at `http://localhost:8899` for development, devnet for demos. Mainnet is out of scope.
- **Decisions D1–D8** from the implementation plan are in effect: permissioned monitors (D1), one report per monitor per window (D2), per-slot `k`-of-`n` consensus (D3), all-or-nothing payout (D4), customer-funded escrow (D5), anyone can crank (D6), and a fixed monitor list chosen by the customer (D8).

## Accounts

All integers are little-endian (Borsh). Every account starts with Anchor's 8-byte discriminator.

| Account | Seeds | Fields |
|---|---|---|
| `Config` | `["config"]` | `admin`, `window_secs: u32`, `report_grace_secs: u32`, `max_monitors_per_sla: u8`, `bump` |
| `Monitor` | `["monitor", authority]` | `authority`, `name: String` (≤32 bytes), `active`, `reports_submitted: u64`, `slots_voted: u64`, `slots_agreed: u64`, `bump` |
| `Sla` | `["sla", customer, sla_id: [u8;16]]` | `customer`, `provider`, `sla_id`, `monitors: Vec<Pubkey>` (≤5), `name` (≤64), `endpoint` (≤200), `escrow_lamports: u64`, `required_uptime_bps: u16`, `start_ts: i64`, `end_ts: i64`, `check_interval_secs: u32`, `timeout_ms: u32`, `consensus_required: u8`, `window_secs: u32`, `report_grace_secs: u32`, `total_windows: u32`, `next_window_to_finalize: u32`, `up_checks: u64`, `counted_checks: u64`, `window_results: Vec<{up: u16, counted: u16}>`, `settled: bool`, `recipient: Option<Recipient>`, `bump` |
| `WindowReport` | `["window", sla, window_index as u32 LE]` | `sla`, `window_index: u32`, `per_monitor: [{checked: [u8;32], up: [u8;32], submitted: bool}; 5]`, `payer`, `bump` |

`Recipient` is the enum `Provider | Customer`.

- `Monitor.authority` is the wallet the monitor node signs with. `Sla.monitors` stores these authorities, not the `Monitor` PDAs.
- `Sla.window_secs` and `Sla.report_grace_secs` are copied from `Config` at creation, so the schedule of a live SLA never changes.
- The `Sla` account is sized for exactly `total_windows` results: `Sla::space(total_windows)`, at most about 9.3 KB, under the 10 KiB CPI allocation limit.
- **Escrow** lives in the `Sla` account as lamports above its rent-exempt minimum. The rent stays in the account after settlement.
- `WindowReport.per_monitor[i]` belongs to `Sla.monitors[i]`. Entries past `monitors.len()` stay zeroed.

**Fixed `Sla` offsets for `getProgramAccounts` memcmp filters.** These are pinned by `tests/test_limits.rs`, so don't reorder these fields:

| Bytes | Field |
|---|---|
| 8..40 | `customer` |
| 40..72 | `provider` |
| 72..88 | `sla_id` |
| 88..92 | `monitors.len()` (u32) |
| 92 + 32·i .. 124 + 32·i | `monitors[i]` |

A monitor node finds its SLAs with up to 5 queries, one per position `i`: memcmp `92 + 32·i` = its authority.

## Time, windows and check slots

Let `W = sla.window_secs`, `G = sla.report_grace_secs`, and `I = sla.check_interval_secs`. All times are Unix seconds from the `Clock` sysvar.

- `total_windows = ceil(duration_secs / W)`.
- Window `i` (where `0 ≤ i < total_windows`) covers `[start_i, end_i)`, with `start_i = start_ts + i·W` and `end_i = min(start_i + W, end_ts)`. The last window may be shorter.
- The window has `slots_i = ceil((end_i − start_i) / I)` check slots, which is at most 256. Slot `j` is the check due at `start_i + j·I`.
- **Bitmaps:** bit `j` is byte `j / 8`, bit `j % 8` (LSB first). `checked` marks slots the monitor actually checked. `up` marks the subset that returned a 2xx response within `timeout_ms`.
- **Reporting:** a report for window `i` is accepted while `end_i ≤ now < end_i + G`.
- **Finalizing:** window `i` can be finalized once `now ≥ end_i + G`, strictly in order.
- **Settling:** allowed once `now ≥ end_ts + G` and all windows are finalized.

## Instructions

Error names refer to `SlaError` (below).

### `initialize_config(params: ConfigParams { window_secs, report_grace_secs, max_monitors_per_sla })`
Accounts: `admin` (signer, writable, payer), `config` (init), `system_program`.
- Runs once, because `config` is `init`. The signer becomes `admin`. Defaults are `DEFAULT_WINDOW_SECS` (3600), `DEFAULT_REPORT_GRACE_SECS` (600), and `MAX_MONITORS_PER_SLA` (5). Tests and demos may use shorter windows.
- Requires `10 ≤ window_secs ≤ 86_400`, `1 ≤ report_grace_secs ≤ 86_400`, and `1 ≤ max_monitors_per_sla ≤ 5`. Otherwise it fails with `InvalidConfig`.
- MVP caveat: the first caller becomes admin. Deploy and initialize in one step. (Restricting this to the upgrade authority is Phase 3.)

### `register_monitor(name: String)`
Accounts: `admin` (signer, writable, payer), `config`, `authority` (the monitor wallet, unchecked), `monitor` (init), `system_program`.
- The signer must be `config.admin` (`Unauthorized`). `name` must be 1..=32 bytes (`InvalidMonitorName`).
- The monitor is created with `active = true` and all counters at 0.

### `set_monitor_active(active: bool)`
Accounts: `admin` (signer), `config`, `monitor` (writable).
- The signer must be `config.admin` (`Unauthorized`). Deactivating a monitor only blocks its selection for **new** SLAs. Existing SLAs keep accepting its reports.

### `create_sla(sla_id: [u8;16], params: CreateSlaParams, monitors: Vec<Pubkey>)`
`CreateSlaParams = { provider, name, endpoint, escrow_lamports, required_uptime_bps, duration_secs, check_interval_secs, timeout_ms, consensus_required }`.
Accounts: `customer` (signer, writable, payer), `config`, `sla` (init), `system_program`. Remaining accounts: the `Monitor` PDA of each entry of `monitors`, in order (read-only).
- The client picks `sla_id` (16 random bytes). The PDA is `["sla", customer, sla_id]`.

Validation (error on failure):

| Rule | Error |
|---|---|
| `1 ≤ monitors.len() ≤ config.max_monitors_per_sla` | `InvalidMonitorCount` |
| No duplicates in `monitors` | `DuplicateMonitor` |
| Remaining accounts are exactly the `Monitor` PDAs of `monitors`, in order, owned by this program | `MonitorAccountMismatch` |
| Every one of those monitors is `active` | `MonitorInactive` |
| `1 ≤ consensus_required ≤ monitors.len()` | `InvalidConsensus` |
| `name` is 1..=64 bytes | `InvalidSlaName` |
| `endpoint` is 1..=200 bytes and starts with `https://` | `InvalidEndpoint` |
| `escrow_lamports > 0` | `ZeroEscrow` |
| `1 ≤ required_uptime_bps ≤ 10_000` | `InvalidUptimeTarget` |
| `1 ≤ duration_secs ≤ 90 days` and `total_windows ≤ 2_160` | `InvalidDuration` |
| `check_interval_secs ≥ 10` and `ceil(W / check_interval_secs) ≤ 256` | `InvalidCheckInterval` |
| `100 ≤ timeout_ms ≤ 30_000` and `timeout_ms < check_interval_secs · 1000` | `InvalidTimeout` |
| `provider ≠ customer` | `ProviderIsCustomer` |
| `provider ≠ Pubkey::default()` | `InvalidParams` |

Effect:
- `start_ts = now` and `end_ts = now + duration_secs`.
- `W` and `G` are copied from config. `next_window_to_finalize = 0`, totals are 0, `window_results` is empty, `settled = false`, and `recipient = None`.
- `escrow_lamports` is transferred from the customer to the `sla` account by a System Program CPI.
- Emits `SlaCreated`.

### `submit_report(window_index: u32, checked: [u8;32], up: [u8;32])`
Accounts: `monitor_authority` (signer, writable, rent payer), `monitor` (PDA of the signer, writable), `sla`, `window_report` (`init_if_needed`), `system_program`.

| Rule | Error |
|---|---|
| The signer is in `sla.monitors` (position `p`) | `NotAssignedMonitor` |
| `window_index < total_windows` | `InvalidWindowIndex` |
| `now ≥ end_i` | `WindowNotEnded` |
| `now < end_i + G` | `ReportDeadlinePassed` |
| `per_monitor[p].submitted == false` | `DuplicateReport` |
| `up & !checked == 0`, and no bit at or above `slots_i` is set in either bitmap | `InvalidBitmap` |

Effect:
- On first creation, sets `sla`, `window_index`, `payer = monitor_authority`, and `bump`. The handler must tell a fresh account from an existing one, for example by `sla == Pubkey::default()`.
- Stores the bitmaps with `submitted = true` and increments `monitor.reports_submitted`.
- Emits `ReportSubmitted`.
- The monitor does not need to be `active` here.

### `finalize_window(window_index: u32)`
Accounts: `sla` (writable), `window_report` (the window's PDA, always passed, writable), `payer` (writable). Remaining accounts: the `Monitor` PDA of each entry of `sla.monitors`, in order (writable).

| Rule | Error |
|---|---|
| `window_index < total_windows` | `InvalidWindowIndex` |
| `window_index == next_window_to_finalize` | `WindowOutOfOrder` |
| `now ≥ end_i + G` | `ReportDeadlineNotPassed` |
| If `window_report` is initialized: it is owned by this program, and `payer == window_report.payer` | `PayerMismatch` |
| The remaining accounts match `sla.monitors` | `MonitorAccountMismatch` |

Effect, with `k = consensus_required`, applied to each slot `j < slots_i` (**D3**):
- `votes` = monitors with `checked` bit `j` set. `ups` = monitors with `up` bit `j` set.
- If `ups ≥ k`, the slot is **UP** (counted). Otherwise, if `votes ≥ k`, it is **DOWN** (counted). Otherwise it is **not counted**.
- If there is no `WindowReport`, every slot is not counted.
- Agreement stats: for each monitor with `checked` bit `j` set, `slots_voted += 1`. If the slot was counted and the monitor's `up` bit equals the outcome, `slots_agreed += 1`.
- Push `{up, counted}` to `window_results`, add the counts to `up_checks` and `counted_checks`, and increment `next_window_to_finalize`.
- Close `window_report`, refunding its lamports to `payer`.
- Emits `WindowFinalized`.

### `settle()`
Accounts: `sla` (writable), `customer` (writable, must be `sla.customer`), `provider` (writable, must be `sla.provider`).

| Rule | Error |
|---|---|
| `settled == false` | `AlreadySettled` |
| `now ≥ end_ts + G` | `NotYetSettleable` |
| `next_window_to_finalize == total_windows` | `WindowsNotFinalized` |

Effect (**D4**):
- `recipient = Provider` if `counted_checks > 0` and `up_checks · 10_000 ≥ required_uptime_bps · counted_checks` (compare in u128). Otherwise `recipient = Customer`.
- Move exactly `escrow_lamports` from `sla` to the recipient by direct lamport arithmetic. The program owns `sla`.
- Set `settled = true` and `recipient`. Emits `SlaSettled`.

## Events

| Event | Fields |
|---|---|
| `SlaCreated` | `sla`, `customer`, `provider`, `sla_id`, `escrow_lamports`, `required_uptime_bps`, `start_ts`, `end_ts`, `total_windows`, `monitors` |
| `ReportSubmitted` | `sla`, `window_index`, `monitor` (authority), `checked`, `up`, `timestamp` |
| `WindowFinalized` | `sla`, `window_index`, `up`, `counted`, `had_reports` |
| `SlaSettled` | `sla`, `recipient`, `amount_lamports`, `up_checks`, `counted_checks` |

The frontend reads recent `ReportSubmitted` events from transaction logs to show per-monitor observations.

## Errors

Anchor custom error codes start at 6000, in declaration order. Append new variants only; never reorder.

| Code | Name | Code | Name |
|---|---|---|---|
| 6000 | `Unauthorized` | 6015 | `MonitorAccountMismatch` |
| 6001 | `InvalidConfig` | 6016 | `MonitorInactive` |
| 6002 | `InvalidMonitorName` | 6017 | `NotAssignedMonitor` |
| 6003 | `InvalidParams` | 6018 | `InvalidWindowIndex` |
| 6004 | `InvalidSlaName` | 6019 | `WindowNotEnded` |
| 6005 | `InvalidEndpoint` | 6020 | `ReportDeadlinePassed` |
| 6006 | `ZeroEscrow` | 6021 | `DuplicateReport` |
| 6007 | `InvalidUptimeTarget` | 6022 | `InvalidBitmap` |
| 6008 | `InvalidDuration` | 6023 | `WindowOutOfOrder` |
| 6009 | `InvalidCheckInterval` | 6024 | `ReportDeadlineNotPassed` |
| 6010 | `InvalidTimeout` | 6025 | `PayerMismatch` |
| 6011 | `ProviderIsCustomer` | 6026 | `NotYetSettleable` |
| 6012 | `InvalidMonitorCount` | 6027 | `WindowsNotFinalized` |
| 6013 | `DuplicateMonitor` | 6028 | `AlreadySettled` |
| 6014 | `InvalidConsensus` | 6029 | `MathOverflow` |
