# CLAUDE.md — uptime-deal

The `uptime_deal` Solana program (Rust, Anchor 1.1.2). It is an uptime agreement between a payer (the customer) and a recipient (the provider), and both put money in. The payer proposes the deal and locks its payment. The recipient accepts it by signing the same terms and locking a guarantee; only then does the uptime window start on chain. An oracle reports the application's measured uptime over that window. If uptime is strictly above 99%, the recipient receives both deposits; otherwise the payer does. A proposal can be withdrawn or rejected at any time, and if the oracle never settles an accepted deal, either party can cancel it after a timeout and each gets its own deposit back. Keep this file in step with changes to the module's behavior, architecture, or commands.

## Flow

1. `create_deal(deal_id: u64, amount_lamports: u64, guarantee_lamports: u64, duration_seconds: u64)`, signed by the **payer**. It creates the `Deal` PDA (seeds `["deal", payer, deal_id (u64 LE)]`) as a **proposal** (`status = Proposed`, `starts_at = 0`). It stores the payer, the `recipient` and `oracle` addresses passed as accounts, the payment, the guarantee the recipient must lock, `duration_seconds`, and `accept_deadline` (chain time + `ACCEPT_TIMEOUT_SECONDS`, 86,400). It then moves only `amount_lamports` from the payer into the deal. Rules: both amounts `>= MIN_DEAL_LAMPORTS` (1,000,000, above the rent-exempt minimum of an empty wallet), recipient ≠ payer, and `1 <= duration_seconds <= MAX_DEAL_DURATION_SECONDS` (86,400).
2. `accept_deal(amount_lamports, guarantee_lamports, duration_seconds, oracle: Pubkey)`, signed by the deal's **recipient** (`has_one = recipient`). The arguments are the terms the recipient agrees to; the program rejects the acceptance unless they equal the stored terms (`TermsMismatch`). Without this check, a payer could cancel a proposal and re-create it at the same address (same `deal_id`) with harsher terms while the recipient's acceptance is in flight. It also needs `status == Proposed` (`DealNotProposed`) and `now < accept_deadline` (`AcceptExpired`). It moves `guarantee_lamports` from the recipient into the deal, sets `starts_at` to the chain clock and `status = Active`. The window is `[starts_at, starts_at + duration_seconds)`; because it is on chain, whoever registers the deal with the oracle can't change it.
3. `settle_deal(up_seconds: u64, total_seconds: u64)`, signed by the deal's **oracle**. It needs `status == Active` (`DealNotActive`), `total_seconds > 0` and `up_seconds <= total_seconds`. If `up_seconds * 100 > total_seconds * 99` (exact integer math), both deposits go to the recipient; otherwise they stay in the deal. Anchor's `close = payer` then closes the deal and returns its rent, and on a refund both deposits too, to the payer. A settled deal no longer exists, so it can't be settled twice, and its `deal_id` can be reused.
4. `cancel_deal()`, signed by the deal's **payer or recipient** (anyone else gets `NotAParty`). The accounts are `signer`, `deal`, `payer` and `recipient`. A **proposal** can be cancelled at any time: the payer withdraws it, or the recipient rejects it. An **accepted** deal can be cancelled once the chain time is at least `starts_at + duration_seconds + CANCEL_TIMEOUT_SECONDS` (600; `CancelTooEarly` before). In that case the guarantee goes back to the recipient first. In both cases the deal closes to the payer, returning the payment and the rent. Until the timeout only the oracle can close an accepted deal; after it, all three can, and whichever lands first wins. This means a lost oracle key, a restarted or failed oracle, or a deal that was never registered can't lock the deposits forever.

The uptime figures match `uptime-service`, which records per-second up/down history. The oracle is `uptime-service` itself: it registers deals via `POST /api/deals` (usually while they are still proposals), picks up the acceptance from the chain, and settles them with its own key (see `uptime-service/CLAUDE.md`, "Uptime deals"). On the frontend `/deal` page the payer proposes and the provider accepts with their wallets. The program trusts the oracle's numbers. It does not check that the window is over when the oracle settles: the oracle's chain clock may differ from its own.

Events: `DealCreated` (terms and `accept_deadline`), `DealAccepted` (`starts_at`), `DealSettled` (`paid_to_recipient` tells which side got both deposits; then `amount_lamports`, `guarantee_lamports`) and `DealCancelled` (`cancelled_by`, `guarantee_refunded_lamports`). New event fields go at the end, because `uptime-service` decodes event prefixes. Errors (`DealError`, codes 6000+, append only): `AmountTooSmall`, `RecipientIsPayer`, `UnauthorizedOracle`, `InvalidUptime`, `InvalidDuration`, `CancelTooEarly`, `GuaranteeTooSmall`, `DealNotProposed`, `DealNotActive`, `AcceptExpired`, `TermsMismatch`, `NotAParty`. If `payer` or `recipient` doesn't match the deal, Anchor fails `settle_deal` and `cancel_deal` (and `accept_deal`, for a signer other than the recipient) with `ConstraintHasOne` (2001).

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
- `instructions/create_deal.rs`, `instructions/accept_deal.rs`, `instructions/settle_deal.rs`, `instructions/cancel_deal.rs`: each holds its `#[derive(Accounts)]` struct and handler.
- `logic.rs`: the pure rules with no Solana types (`uptime_above_threshold`, `amount_is_valid`, `duration_is_valid`, `cancel_allowed`, `accept_deadline`, `accept_open`, and `Terms` with `terms_match`), with unit tests in the same file.
- `state.rs` (`Deal`, `DealStatus`). The `Deal` account is 154 bytes: discriminator, payer, recipient, oracle, deal_id, amount_lamports, guarantee_lamports, duration_seconds, accept_deadline, starts_at, status (1 byte: 0 `Proposed`, 1 `Active`), bump. `uptime-service` decodes this layout by hand (`DealProgram`), so update it there too.
- `constants.rs` (`DEAL_SEED`, `UPTIME_THRESHOLD_PERCENT`, `MIN_DEAL_LAMPORTS`, `MAX_DEAL_DURATION_SECONDS`, `CANCEL_TIMEOUT_SECONDS`, `ACCEPT_TIMEOUT_SECONDS`, exported in the IDL), `error.rs`, `events.rs`.
- `tests/`: LiteSVM tests run the built program in-process, with no validator. `common/mod.rs` is the harness: it holds the parties (all funded, since the recipient pays the guarantee), has instruction builders, and has `propose`/`accept`/`open` helpers.
  - `test_e2e.rs` covers the full propose → accept → report → payout flows: both deposits to the recipient above 99%, both to the payer at or below, the strict boundary, multiple deals with lamport conservation, settling twice, withdrawing and rejecting a proposal, either party cancelling an accepted deal after the timeout, and a late settlement beating the cancel.
  - `test_errors.rs` covers every rejection and checks that it moves no lamports. That includes accepting with each mismatched term, and a proposal replaced at the same address while its acceptance is in flight.

## Conventions

Every public function, including the test harness helpers, has a rustdoc comment in javadoc style: a summary of what it does, then `# Arguments` for each parameter, `# Returns`, and `# Errors` when it can fail. Keep that format for new code.

## Program ID and keys

The program ID is `EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r`, used in `declare_id!` and `Anchor.toml`. Its deploy keypair, `target/deploy/uptime_deal-keypair.json`, is gitignored. The tests don't need it because LiteSVM loads the `.so` under `declare_id!`. To deploy from a fresh clone, copy the keypair in, or run `anchor keys sync` to adopt a new ID.
