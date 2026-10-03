# CLAUDE.md — uptime-deal

The `uptime_deal` Solana program (Rust, Anchor 1.1.2). It is a simple uptime-conditioned payment between two accounts. A payer locks lamports in escrow for a recipient. An oracle reports the application's measured uptime. If uptime is strictly above 99%, the recipient receives the escrow; otherwise the payer gets it back. Keep this file in step with changes to the module's behavior, architecture, or commands.

## Flow

1. `create_deal(deal_id: u64, amount_lamports: u64)`, signed by the **payer**. It creates the `Deal` PDA (seeds `["deal", payer, deal_id (u64 LE)]`) and stores the payer, the `recipient` and `oracle` addresses passed as accounts, and the amount. It then moves `amount_lamports` from the payer into the deal. Rules: `amount_lamports >= MIN_DEAL_LAMPORTS` (1,000,000, which is above the rent-exempt minimum of an empty wallet, so paying a recipient that doesn't exist yet works), and recipient ≠ payer.
2. `settle_deal(up_seconds: u64, total_seconds: u64)`, signed by the deal's **oracle**. It needs `total_seconds > 0` and `up_seconds <= total_seconds`. If `up_seconds * 100 > total_seconds * 99` (exact integer math), the escrow goes to the recipient; otherwise it stays in the deal. Anchor's `close = payer` then closes the deal and returns its rent, and on a refund the escrow too, to the payer. A settled deal no longer exists, so it can't be settled twice, and its `deal_id` can be reused.

The uptime figures match `uptime-service`, which records per-second up/down history. The oracle is `uptime-service` itself: it registers deals via `POST /api/deals` and settles them with its own key (see `uptime-service/CLAUDE.md`, "Uptime deals"). The frontend `/deal` page creates deals with the wallet. The program trusts the oracle's numbers, and there is no timeout or cancel: if the oracle never settles, the escrow stays locked.

Events: `DealCreated` and `DealSettled` (`paid_to_recipient` tells which side got the escrow). Errors (`DealError`, codes 6000+, append only): `AmountTooSmall`, `RecipientIsPayer`, `UnauthorizedOracle`, `InvalidUptime`. If `payer` or `recipient` doesn't match the deal, Anchor fails `settle_deal` with `ConstraintHasOne` (2001).

## Commands

Run from `uptime-deal/`. The tools come from `scripts/setup-toolchain.sh`. If they aren't on `PATH`, add `~/.cargo/bin` and `~/.local/share/solana/install/active_release/bin`.

```bash
anchor build                          # target/deploy/uptime_deal.so + IDL (target/idl/uptime_deal.json)
cargo test                            # all tests (needs a prior anchor build: tests load the .so)
cargo test --test test_e2e            # one test file
cargo test threshold_boundary         # single test by name
```

`scripts/test-all.sh` runs `anchor build && cargo test`. Rerun `anchor build` after changing the program, or the LiteSVM tests run the old `.so`.

## Layout

- `programs/uptime_deal/src/lib.rs`: the `#[program]` entry points, which delegate to `instructions/<name>.rs::handle_<name>`.
- `instructions/create_deal.rs`, `instructions/settle_deal.rs`: each holds its `#[derive(Accounts)]` struct and handler.
- `logic.rs`: the pure rules with no Solana types (`uptime_above_threshold`, `amount_is_valid`), with unit tests in the same file.
- `state.rs` (`Deal`), `constants.rs` (`DEAL_SEED`, `UPTIME_THRESHOLD_PERCENT`, `MIN_DEAL_LAMPORTS`, exported in the IDL), `error.rs`, `events.rs`.
- `tests/`: LiteSVM tests run the built program in-process, with no validator. `common/mod.rs` is the harness: it holds the parties and has instruction builders. `test_e2e.rs` covers the full fund → report → payout flows: pay above 99%, refund at or below, the strict boundary, multiple deals with lamport conservation, and settling twice. `test_errors.rs` covers every rejection and checks that it moves no lamports.

## Conventions

Every public function, including the test harness helpers, has a rustdoc comment in javadoc style: a summary of what it does, then `# Arguments` for each parameter, `# Returns`, and `# Errors` when it can fail. Keep that format for new code.

## Program ID and keys

The program ID is `EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r`, used in `declare_id!` and `Anchor.toml`. Its deploy keypair, `target/deploy/uptime_deal-keypair.json`, is gitignored. The tests don't need it because LiteSVM loads the `.so` under `declare_id!`. To deploy from a fresh clone, copy the keypair in, or run `anchor keys sync` to adopt a new ID.
