# CLAUDE.md — solana-escrow

This module is the on-chain settlement authority for SLA agreements. It codifies the escrow state machine, evaluates monitoring availability, and settles funds without any backend override.

## Commands

Run from `solana-escrow/`:

```bash
anchor build
anchor test
anchor deploy
```

## Architecture

- `programs/solana-escrow/src/lib.rs` contains the Anchor program and the escrow account layout.
- The agreement state machine models: `created`, `customer_funded`, `monitoring`, `evaluating`, and `settled`.
- The program records the customer/provider wallets, escrow amount, evaluation window, and the final availability snapshot that decides payout.
- `settle` applies the canonical rule: if `availability_bps < 9900`, the customer receives a 30% refund; otherwise, the provider receives the full escrow value. The final state is immutable after settlement.
- The frontend reads program state and displays it; it never overrides the result.

## Settlement rule

`availability_bps` is the canonical source of truth. The program compares it against a 99.0% threshold and executes the payout using the escrow amount already held in the account.
