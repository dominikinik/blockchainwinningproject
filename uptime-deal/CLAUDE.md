# CLAUDE.md — uptime-deal

The `uptime_deal` Solana program (Rust, Anchor 1.1.2). It is an uptime SLA whose outcome is decided on chain. A customer (the **payer**) locks a payment, and the provider (the **recipient**) can be required to lock a performance guarantee. A monitor (the **oracle**) records, round by round, whether the service was UP or DOWN. The program keeps those counters in the deal account. Once the window is over, **anyone** can call `settle_deal`. The program compares its own counters with the deal's threshold and pays the whole escrow (payment plus guarantee) to the provider when the SLA is met, or to the customer on a breach. Monitors observe; the program decides. Keep this file in step with changes to the module's behavior, architecture, or commands.

## Flow

1. `create_deal(deal_id: u64, amount_lamports: u64, provider_stake_lamports: u64, duration_seconds: u64, check_interval_seconds: u64, min_uptime_bps: u16)`, signed by the **payer**. It creates the `Deal` PDA (seeds `["deal", payer, deal_id (u64 LE)]`) and moves `amount_lamports` into it. It stores the `recipient` and `oracle` addresses passed as accounts, the terms, and `total_rounds = duration / interval`, and it allocates a bitmap with one bit per round. Rules:
   - `amount_lamports >= MIN_DEAL_LAMPORTS` (1,000,000, which is above the rent-exempt minimum of an empty wallet, so paying a recipient that doesn't exist yet works);
   - recipient ≠ payer;
   - `1 <= duration_seconds <= MAX_DEAL_DURATION_SECONDS` (86,400);
   - the interval divides the duration into 1..`MAX_ROUNDS` (8,192) rounds;
   - `1 <= min_uptime_bps <= 10_000`.

   With `provider_stake_lamports == 0` the deal is `Active` at once and the window starts at the chain clock (`starts_at`). Otherwise the deal is `AwaitingProvider` and `starts_at` is 0.
2. `accept_deal()`, signed by the **recipient**. It moves `provider_stake_lamports` into the deal, sets `Active`, and starts the window now. A deal can be accepted once.
3. `record_observation(round: u32, up: bool)`, signed by the deal's **oracle**. It adds one round to `up_checks` or `down_checks` and sets the round's bit. It requires all of the following:
   - the deal is `Active`;
   - `round < total_rounds`;
   - the round has **ended** on the chain clock (`now >= starts_at + (round + 1) * interval`);
   - observations are still open (`now < ends_at + OBSERVATION_GRACE_SECONDS`, which is 10);
   - the round's bit is not set yet.

   Rounds may arrive in any order, and none can be counted twice. The oracle reports only UP or DOWN. It never sends totals or a verdict.
4. `settle_deal()`, signed by **anyone**: the signer only pays the fee. It has no arguments. It requires an `Active` deal and `now >= ends_at + OBSERVATION_GRACE_SECONDS`, exactly when observations close, so the outcome can't depend on who acts first. The verdict is `up_checks * 10_000 >= min_uptime_bps * total_rounds`, in exact u64 integer math. **Rounds that were never observed count as down.**
   - If the SLA is met, the payment plus the guarantee go to the recipient.
   - Otherwise the escrow stays in the deal, and Anchor's `close = payer` returns it to the payer together with the rent. The rent always goes back to the payer.

   A settled deal no longer exists, so it can't be settled twice (3012), and its `deal_id` can be reused.
5. `cancel_deal()`, signed by the **payer**, works only while the deal is `AwaitingProvider`. It closes the deal and returns the payment and the rent. An active deal can't be cancelled; settling it is permissionless, so its escrow is never stuck.

Uptime-service is the default oracle. It samples its own health during each round and sends `record_observation` once the round ends. Its Postgres history (`uptime-db`) is for the dashboard only and is never read for settlement. See `uptime-service/CLAUDE.md`, "Uptime deals". The frontend `/deal` page creates, accepts and settles deals with the wallet, and reads the counters straight from the account.

Trust model: the oracle is trusted to report honestly what it saw. It can't invent rounds that haven't ended, count a round twice, report after the grace period, change the terms, or move funds. If it goes silent, its rounds count as down. Neither party can feed the counters.

Events:
- `DealCreated` (the terms), `DealStarted` (`starts_at`, `ends_at`), `ObservationRecorded` (the round, `up`, the counters after it), `DealSettled` (the counters, `min_uptime_bps`, `paid_to_recipient`, `payout_lamports`) and `DealCancelled`. Each starts with the deal's pubkey.

Errors (`DealError`, codes 6000+, append only):
- `AmountTooSmall`, `RecipientIsPayer`, `UnauthorizedOracle`, `InvalidUptime` (no longer returned), `InvalidDuration`, `CancelTooEarly` (no longer returned), `InvalidCheckInterval`, `InvalidThreshold`, `DealNotActive`, `DealAlreadyActive`, `RoundOutOfRange`, `RoundNotEnded`, `RoundAlreadyRecorded`, `ObservationsClosed`, `SettleTooEarly`, `Overflow`.
- If `payer` or `recipient` doesn't match the deal, Anchor fails `settle_deal`, `accept_deal` and `cancel_deal` with `ConstraintHasOne` (2001).

## Account layout

`Deal` (Borsh, after the 8-byte discriminator), in order:
- `payer`, `recipient`, `oracle`: 32-byte pubkeys;
- `deal_id`, `amount_lamports`, `provider_stake_lamports`: u64;
- `status`: u8, 0 = `AwaitingProvider`, 1 = `Active`;
- `starts_at`: i64;
- `duration_seconds`, `check_interval_seconds`: u64;
- `min_uptime_bps`: u16;
- `total_rounds`, `up_checks`, `down_checks`: u32;
- `bump`: u8;
- `recorded`: u32 length prefix, then the bitmap. Bit `r % 8` of byte `r / 8` is set once round `r` is recorded.

The account size is `Deal::space(total_rounds)`, which is `Deal::FIXED_SPACE` (183) plus `ceil(rounds / 8)`. `uptime-service` (`DealProgram.java`) and the frontend (`dealProgram.ts`) decode this layout by hand. Change all three together.

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

- `programs/uptime_deal/src/lib.rs`: the `#[program]` entry points. Each delegates to `instructions/<name>.rs::handle_<name>`.
- `instructions/{create_deal,accept_deal,record_observation,settle_deal,cancel_deal}.rs`: each holds its `#[derive(Accounts)]` struct and handler. `create_deal::start` activates a deal; `accept_deal` uses it too.
- `logic.rs`: the pure rules with no Solana types (`sla_met`, `total_rounds`, `round_ended`, `observations_open`, `settle_allowed`, the bitmap helpers `is_recorded` / `mark_recorded`, and the validators), with unit tests in the same file.
- `state.rs` (`Deal`, `DealStatus`).
- `constants.rs` (`DEAL_SEED`, `BPS_DENOMINATOR`, `MIN_DEAL_LAMPORTS`, `MAX_DEAL_DURATION_SECONDS`, `MAX_ROUNDS`, `OBSERVATION_GRACE_SECONDS`, exported in the IDL).
- `error.rs`, `events.rs`.
- `tests/`: LiteSVM tests run the built program in-process, with no validator.
  - `common/mod.rs` is the harness. It holds the parties, starts the chain at `T0`, and has instruction builders, `Terms` (default: 10 rounds of 6 s at 90%), `observe_all` / `pattern(up, down, missed)` and `to_settlement`.
  - `test_e2e.rs` covers the full flows:
    - UP and DOWN observations updating the account, and out-of-order rounds;
    - the provider winning payment plus guarantee, and the customer winning both;
    - the inclusive threshold boundary, unobserved rounds counting as down, and a stranger settling from on-chain state alone;
    - settling twice, lamport conservation across deals, and cancelling an unaccepted deal.
  - `test_errors.rs` covers every rejection: the create terms; accept by the wrong signer, twice, or without funds; observations from unauthorized signers, duplicate rounds, out-of-range, unfinished or late rounds, or a deal not yet active; settlement too early, before acceptance, or with swapped wallets; and cancel. Each checks that nothing moved.

## Conventions

Every public function, including the test harness helpers, has a rustdoc comment in javadoc style: a summary of what it does, then `# Arguments` for each parameter, `# Returns`, and `# Errors` when it can fail. Keep that format for new code.

## Program ID and keys

The program ID is `EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r`, used in `declare_id!` and `Anchor.toml`. Its deploy keypair, `target/deploy/uptime_deal-keypair.json`, is gitignored. The tests don't need it, because LiteSVM loads the `.so` under `declare_id!`. To deploy from a fresh clone, copy the keypair in, or run `anchor keys sync` to adopt a new ID. The account layout changed incompatibly in the on-chain settlement refactor: redeploy (or restart the local validator) and create new deals. Deals created by the old program can't be decoded.
