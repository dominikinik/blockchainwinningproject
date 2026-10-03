# Mission-Critical Plan: Solana Escrow SLA

## Goal

Replace trust-based SLA enforcement with a Solana smart contract that locks funds, evaluates API uptime, and settles payments automatically.

## Rules

- Availability is already calculated from the project’s monitoring data.
- Independent monitoring services submit signed observations.
- If availability is below 99.0%, the customer receives a 30% refund of the fee.
- Escrow is in SOL.
- The calculated availability is the source of truth; no dispute process in v1.
- The Java service supports monitoring and data collection, but the Solana program decides settlement.

## Current state

- Java uptime service records up/down state per second and exposes history through REST.
- PostgreSQL stores uptime history.
- Frontend currently shows mocked SLA data and mock settlement flow.
- The repo already documents that the application should move from mock SLA logic to real on-chain settlement authority.

## Implementation plan

1. Define the contract state machine
   - agreement created
   - customer-funded escrow locked
   - monitoring active
   - SLA window evaluated
   - settled / paid / refunded

2. Implement the escrow contract
   - customer and provider wallets
   - escrow amount in SOL
   - SLA metadata and evaluation window
   - on-chain settlement rules
   - protection against double settlement and invalid state transitions

3. Integrate monitor data
   - use the existing monitoring model and signed observations
   - accept the computed availability value as the canonical SLA input
   - record the final result in contract state

4. Encode settlement logic
   - if availability < 99.0% => refund = 30% of fee
   - otherwise transfer the full expected result to the provider
   - no backend override of the program result

5. Connect the app
   - frontend wallet flow for agreement creation and settlement status
   - real on-chain state instead of localStorage/mock fields
   - backend remains operational support only

## Risks to handle

- stale or missing monitoring data
- time-window edge cases
- incorrect calculation input or rounding
- contract state mismatch during settlement

## Acceptance criteria

- contract creates escrowed SLA agreement
- monitoring data feeds the agreement outcome
- below 99.0% availability triggers a 30% refund
- settlement is final and on-chain
- no backend can override the final outcome
- frontend shows real agreement and settlement status
