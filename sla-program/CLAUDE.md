# CLAUDE.md — sla-program

The `sla` Solana program (Rust, Anchor 1.1.2). It holds SLA escrow, keeps the permissioned monitor registry, collects monitors' per-window check reports, decides each check by `k`-of-`n` consensus, and pays the escrow to the provider or refunds the customer. Keep this file in step with changes to the module's behavior, architecture, or commands.

**[SPEC.md](SPEC.md) is the contract** with `sla-monitor` and `frontend`. It covers accounts and their fixed memcmp offsets, instructions and their validation rules, time windows, bitmaps, consensus and payout math, events, and error codes. Code must match it. To change the interface, update SPEC.md first, then rebuild, run `scripts/sync-idl.sh`, and update the clients in the same change.

## Status

Phase 0 is a stub. Every account, instruction signature, event and error is final, and every handler body is `todo!()`. Task T1 implements the handlers without changing the interface.

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
- `state.rs`: the `Config`, `Monitor`, `Sla` and `WindowReport` accounts, plus `Sla::space` and `Sla::window_count`.
- `constants.rs`: seeds and limits, exported in the IDL. `error.rs`: `SlaError`. Its order fixes the 6000+ codes, so append only. `events.rs`: the events.
- `tests/`: integration tests (LiteSVM, in-process, no validator) and layout and limit tests. Tests that load the program use `include_bytes!` on `target/deploy/sla.so`.

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
