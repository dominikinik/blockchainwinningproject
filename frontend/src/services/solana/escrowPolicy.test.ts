import { describe, expect, it } from 'vitest'
import { evaluateEscrowOutcome } from './escrowPolicy'

describe('evaluateEscrowOutcome', () => {
  it('refunds the customer when availability is under the minimum threshold', () => {
    const outcome = evaluateEscrowOutcome(10, 98.75)

    expect(outcome.recipient).toBe('customer')
    expect(outcome.amountSol).toBe(3)
    expect(outcome.refundSol).toBe(3)
    expect(outcome.threshold).toBe(99)
  })

  it('pays the provider when availability meets the threshold', () => {
    const outcome = evaluateEscrowOutcome(10, 99.1)

    expect(outcome.recipient).toBe('provider')
    expect(outcome.amountSol).toBe(10)
    expect(outcome.refundSol).toBe(0)
  })

  it('handles invalid numeric values safely', () => {
    expect(evaluateEscrowOutcome(Number.NaN, Number.NaN)).toEqual({
      recipient: 'customer',
      amountSol: 0,
      refundSol: 0,
      availability: 0,
      threshold: 99,
    })
  })
})
