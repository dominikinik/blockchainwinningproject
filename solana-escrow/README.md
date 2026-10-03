# Solana escrow module

This module implements the plan in `SOLANA_ESCROW_PLAN.md` as a minimal Anchor program. It creates an escrow agreement for an SLA, tracks the monitoring window, and settles the account on-chain using the accepted availability score.

## State machine

1. `created`
2. `customer_funded`
3. `monitoring`
4. `evaluating`
5. `settled`

## Settlement policy

- availability < 99.0% => customer receives a 30% refund
- availability >= 99.0% => provider receives the full escrow amount
- settlement is final and cannot be replayed

## Local development

```bash
cd solana-escrow
anchor build
anchor test
```
