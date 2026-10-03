# CLAUDE.md — sla-program

The `sla` Solana program (Rust, Anchor 1.1.2). It holds SLA escrow, keeps the permissioned monitor registry, collects monitors' per-window check reports, decides each check by `k`-of-`n` consensus, and pays the escrow to the provider or refunds the customer. Keep this file in step with changes to the module's behavior, architecture, or commands.

**[SPEC.md](SPEC.md) is the contract** with `sla-monitor` and `frontend`. It covers accounts and their fixed memcmp offsets, instructions and their validation rules, time windows, bitmaps, consensus and payout math, events, and error codes. Code must match it. To change the interface, update SPEC.md first, then rebuild, run `scripts/sync-idl.sh`, and update the clients in the same change.

## Status

Phase 1 (T1) is done: every instruction in SPEC.md is implemented, and the interface is unchanged from Phase 0. The generated IDL is byte-identical to the copies in `frontend/` and `sla-monitor/`. The program's doc comment in `lib.rs` still reads "Handlers are stubs until T1", because editing it changes the IDL `docs`. Update it together with the next IDL sync.

Known limitation: `settle` credits the recipient by plain lamport arithmetic. If the recipient wallet doesn't exist yet and `escrow_lamports` is below the rent-exempt minimum for an empty account (890,880 lamports), the runtime rejects the transaction and the escrow can't be settled until someone funds that wallet. `create_sla` has no minimum escrow because SPEC.md doesn't define one.

## Commands

Run from `sla-program/`. The tools come from `scripts/setup-toolchain.sh`: Rust, Agave CLI 3.1.10, and Anchor 1.1.2. If they aren't on `PATH`, add `~/.cargo/bin` and `~/.local/share/solana/install/active_release/bin`.

```bash
anchor build                              # program (target/deploy/sla.so) + IDL (target/idl/sla.json) + TS types (target/types/sla.ts)
cargo test                                # all tests (needs a prior anchor build for target/deploy/sla.so)
cargo test --test test_limits             # one test file
cargo test window_count_rounds_up         # single test by name
../scripts/sync-idl.sh                    # copy the IDL and TS types into frontend/ and sla-monitor/
```

`scripts/test-all.sh` runs `anchor build && cargo test`. The first `anchor build` on a machine downloads Solana's platform-tools (about 400 MB, from GitHub). Later builds are incremental.

## Layout

- `programs/sla/src/lib.rs`: the `#[program]` entry points. Each one delegates to `instructions/<name>.rs::handle_<name>`.
- `instructions/`: one file per instruction, holding its `#[derive(Accounts)]` struct, any params struct, and its handler.
- `instructions.rs`: also holds `read_monitor`/`write_monitor`, which load and check the `Monitor` PDAs passed as remaining accounts to `create_sla` and `finalize_window`.
- `logic.rs`: the pure rules, with no Solana types: config and `create_sla` limits, window and slot math (`Schedule`), bitmap validation, `k`-of-`n` consensus (`tally_window`), and the payout choice (`provider_is_paid`). Handlers call it, and its unit tests sit in the same file.
- `state.rs`: the `Config`, `Monitor`, `Sla` and `WindowReport` accounts, plus `Sla::space`, `Sla::window_count`, and `Sla::schedule`.
- `constants.rs`: seeds and limits, exported in the IDL. `error.rs`: `SlaError`. Its order fixes the 6000+ codes, so append only. `events.rs`: the events.
- `tests/`: LiteSVM instruction tests (in-process, no validator) and layout and limit tests. `common/mod.rs` is the harness: it loads `target/deploy/sla.so` with `include_bytes!`, sets the `Clock` sysvar, and has one builder per instruction. `test_registry.rs` covers config and monitors, `test_create_sla.rs` covers `create_sla`, `test_reports.rs` covers `submit_report` and `finalize_window` (including a worst-case compute-unit check), and `test_settle.rs` covers `settle`, full lifecycles, and lamport conservation. Rerun `anchor build` before `cargo test` after you change the program, or the instruction tests run the old `.so`.

## Program ID and keys

The program ID is `4ACuzhWwVVqtbYgicWzq11BtowhsEEHLcChJn2gVR4R9`, used in `declare_id!`, `Anchor.toml`, and SPEC.md. Its deploy keypair, `target/deploy/sla-keypair.json`, is gitignored. On a fresh clone, copy that keypair in, or run `anchor keys sync` and update the ID everywhere listed above, plus the clients' env.

## Testing notes

- Put the pure logic (bitmap consensus, uptime ratio, payout choice, window and slot math) in a plain Rust module with no Solana types, and unit-test it directly.
- Instruction tests use LiteSVM with a warped `Clock` sysvar (never sleep). Cover each happy path and every `SlaError`.
- Required security cases:
  - a non-assigned signer
  - a duplicate report
  - a late report
  - out-of-order finalization
  - finalization with a hidden `WindowReport`
  - settling early or twice
  - lamport conservation
- `tests/test_limits.rs` pins the `Sla` memcmp offsets and the 10 KiB size limit. Don't loosen it.
