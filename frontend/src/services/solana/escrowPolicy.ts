export type EscrowPayout = {
  recipient: 'provider' | 'customer'
  amountSol: number
  refundSol: number
  availability: number
  threshold: number
}

export function evaluateEscrowOutcome(escrowSol: number, availability: number): EscrowPayout {
  const safeEscrow = Number.isFinite(escrowSol) ? escrowSol : 0
  const safeAvailability = Number.isFinite(availability) ? availability : 0
  const threshold = 99.0
  const belowThreshold = safeAvailability < threshold
  const refundSol = belowThreshold ? Number((safeEscrow * 0.3).toFixed(3)) : 0
  return {
    recipient: belowThreshold ? 'customer' : 'provider',
    amountSol: belowThreshold ? refundSol : safeEscrow,
    refundSol,
    availability: safeAvailability,
    threshold,
  }
}
