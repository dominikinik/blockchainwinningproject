/** Where to create a deal so that the uptime service can settle it. */
export interface DealConfig {
  /** Base58 uptime_deal program id. */
  programId: string
  /** Base58 oracle key to pass to `create_deal`. */
  oracle: string
  /** RPC URL of the cluster the service settles on. */
  rpcUrl: string
}

export type DealStatus = 'ACTIVE' | 'SETTLED' | 'FAILED'

/** A deal as tracked by the uptime service (`GET /api/deals/{address}`). */
export interface TrackedDeal {
  address: string
  payer: string
  recipient: string
  amountLamports: number
  durationSeconds: number
  /** First second of the uptime window (ISO-8601). */
  startsAt: string
  /** End of the window, exclusive (ISO-8601). */
  endsAt: string
  status: DealStatus
  upSeconds: number | null
  totalSeconds: number | null
  /** The program's verdict from its `DealSettled` event; null until settled or when unknown. */
  paidToRecipient: boolean | null
  signature: string | null
  attempts: number
  error: string | null
}

export const dealApi = {
  /**
   * Reads the program id and oracle key the service expects.
   *
   * @returns the deal configuration
   * @throws Error when the service is unreachable or answers with an error
   */
  getConfig(): Promise<DealConfig> {
    return request<DealConfig>('/api/deals/config')
  },

  /**
   * Asks the service to watch a deal already created on chain and to settle it after the window.
   *
   * @param address Base58 deal address
   * @param durationSeconds length of the uptime window in seconds
   * @returns the tracked deal, whose window starts at the next whole second
   * @throws Error with the service's problem detail (invalid deal, wrong oracle, duplicate, RPC failure)
   */
  register(address: string, durationSeconds: number): Promise<TrackedDeal> {
    return request<TrackedDeal>('/api/deals', { method: 'POST', body: JSON.stringify({ address, durationSeconds }) })
  },

  /**
   * Reads a tracked deal.
   *
   * @param address Base58 deal address
   * @returns the tracked deal
   * @throws Error when the deal isn't registered or the service is unreachable
   */
  get(address: string): Promise<TrackedDeal> {
    return request<TrackedDeal>(`/api/deals/${encodeURIComponent(address)}`)
  },

  /**
   * Switches the service's logical health, to simulate an outage during a window.
   *
   * @param up true to start (UP), false to stop (DOWN)
   * @returns the resulting state
   * @throws Error when the service is unreachable
   */
  async setServiceUp(up: boolean): Promise<'UP' | 'DOWN'> {
    const response = await request<{ status: 'UP' | 'DOWN' }>(`/api/application/${up ? 'start' : 'stop'}`, { method: 'POST' })
    return response.status
  },
}

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: { Accept: 'application/json', ...(init.body ? { 'Content-Type': 'application/json' } : {}) },
    cache: 'no-store',
  })
  if (!response.ok) {
    let detail = ''
    try {
      const problem: unknown = await response.json()
      if (typeof problem === 'object' && problem !== null && 'detail' in problem && typeof problem.detail === 'string') detail = problem.detail
    } catch { /* not JSON */ }
    throw new Error(detail || `Uptime service returned ${response.status}.`)
  }
  return response.json() as Promise<T>
}
