/** One oracle probe of a deal's provider and its on-chain report (`GET /api/heartbeats`). */
export interface Heartbeat {
  id: number
  dealAddress: string
  /** Zero-based round of the deal. */
  round: number
  /** ISO-8601 time of the probe. */
  checkedAt: string
  up: boolean
  outcome: 'HEALTHY' | 'DOWN' | 'INTERNAL_ERROR'
  httpStatus: number | null
  detail: string | null
  latencyMs: number
  /** On-chain delivery of `record_observation`: landed, will retry, or given up. */
  report: 'SENT' | 'RETRYING' | 'DROPPED'
  reportError: string | null
  signature: string | null
}

export const heartbeatApi = {
  /**
   * Reads the most recent heartbeats across all deals.
   *
   * @param limit maximum number of items (the server caps it)
   * @returns heartbeats, most recent first
   * @throws Error when the service is unreachable or answers with an error
   */
  getRecent(limit = 50): Promise<Heartbeat[]> {
    return request<Heartbeat[]>(`/api/heartbeats?limit=${encodeURIComponent(String(limit))}`)
  },

  /**
   * Reads the heartbeats of one deal.
   *
   * @param address Base58 deal address
   * @returns heartbeats, most recent first
   * @throws Error when the service is unreachable or answers with an error
   */
  getForDeal(address: string): Promise<Heartbeat[]> {
    return request<Heartbeat[]>(`/api/deals/${encodeURIComponent(address)}/heartbeats`)
  },
}

async function request<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { Accept: 'application/json' }, cache: 'no-store' })
  if (!response.ok) {
    let detail = ''
    try {
      const problem: unknown = await response.json()
      if (typeof problem === 'object' && problem !== null && 'detail' in problem && typeof problem.detail === 'string') detail = problem.detail
    } catch { /* not JSON */ }
    throw new Error(detail || `Heartbeat log returned ${response.status}.`)
  }
  return response.json() as Promise<T>
}
