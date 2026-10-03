# SLAna business logic

## Purpose

SLAna is a neutral tool for proposing, agreeing on, and automatically executing uptime-based agreements between a service provider and its customer.

The pricing model proposes reasonable financial terms. The parties accept or reject them, lock their stakes, and the smart contract enforces the agreed outcome. SLAna replaces third-party adjudication and payment enforcement for the defined uptime condition; the reliability of monitoring remains a separate trust assumption.

SLAna charges no platform fee in this design. Ordinary service billing is outside the agreement.

This document records the business decisions agreed for the demo. It is not a claim that every described capability is already implemented. Unconfirmed details are listed explicitly below.

## Parties and terms

The business example is **AWS and its customer**. AWS is the service provider, not an intermediary selling protection for another provider. In the demo, a participating wallet represents AWS; this does not imply actual AWS participation.

| Term | Rule |
| --- | --- |
| Agreement duration | Selected by the customer. |
| Desired compensation | Selected by the customer; determines the provider's stake. |
| Customer's stake | Proposed by the pricing model using historical uptime. |
| Required uptime | Fixed at **99%** for the demo. |
| Acceptance | Both parties accept or reject the proposed terms. No negotiation or manual override flow in the demo. |
| Monitored service | One agreed HTTPS endpoint. |
| Settlement | Winner takes both stakes. |

The customer's stake is separate from payment for the underlying AWS service. If AWS satisfies the agreement, that stake becomes AWS's money.

The pricing model proposes terms; it does not determine the eventual winner. The exact pricing formula and its parameters remain to be defined.

## Funding and activation

1. The customer selects the duration and desired compensation.
2. The model proposes the customer's stake from historical uptime.
3. Both parties accept the terms and authorize their own deposits.
4. The agreement starts only when **both deposits are funded and monitoring is ready**.
5. If the other party never funds, the deposited stake must be reclaimable. The funding deadline and reclaim mechanics remain to be defined.

## Settlement

### Customer wins: early breach

The agreement settles in the customer's favor as soon as the full-period 99% uptime guarantee has been irreversibly breached. The customer receives its own stake back plus the provider's stake.

The breach budget belongs to the **entire agreed period**, not merely the elapsed portion. A low running uptime percentage alone must not trigger an early loss if the final guarantee could still be met.

### Provider wins: successful completion

If the agreement reaches expiry without breaching the guarantee, AWS receives its own stake back plus the customer's stake.

Exactly **99% uptime passes**; less than 99% breaches. Payouts are binary, not proportional to the size of the breach.

## Monitoring and aggregation

The agreed direction is for aggregation logic to consume events from the uptime service and calculate an outcome using bad events. The demo should use a simple event-based calculation rather than introduce precise failed-duration accounting.

The endpoint check definition is HTTP 2xx within a fixed timeout; other HTTP responses, timeouts, and connection failures count as failed checks. The timeout and observation cadence are not yet specified.

**The exact event-counting formula has not been agreed.** In particular, a bad event might mean a failed periodic check or an outage event. Those are not interchangeable: counting outages alone does not measure uptime because outages can have different lengths.

One candidate, pending confirmation, is:

- Each event represents an equal observation interval.
- Duration and cadence determine the full-period expected event count.
- Bad events exceeding 1% of that count trigger early breach settlement.
- The denominator is the full-period expected count, not the number of events received so far.

This candidate must not be treated as an accepted business rule until the event semantics and counting details are confirmed.

## Open decisions

- What exactly is a bad event: a failed periodic check, a failed aggregation window, or a whole outage?
- What is the precise event-counting formula, including interval boundaries and rounding for short agreements?
- Which component submits the evidence or transaction that triggers early settlement, and does that happen without any user action?
- What funding deadline permits reclaiming a deposit when the counterparty does not fund?
- What historical data, formula, and parameters determine the proposed customer stake?

## Deferred improvements

- Precise failed-duration accounting from timestamped observations, clipped to the agreement window, with the smart contract comparing the reported failed duration against the downtime allowance.
- Monitoring-risk policy, including missing events, monitor outages, and trust arrangements. These are not a demo priority; their treatment is not yet agreed. Missing evidence must not be described as having an agreed payout rule.

## Presentation principles

Lead with the achievements: **model-proposed financial terms, mutual commitments backed by escrow, uptime-based enforcement, and execution of agreed payouts**.

Do not structure the presentation around unfinished work or simplifications. Keep implementation details in technical documentation, while ensuring claims accurately reflect demonstrated capabilities.
