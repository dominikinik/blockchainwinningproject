/** Where to create a deal so that the uptime monitor reports its rounds. */
export interface DealConfig {
  /** Base58 uptime_deal program id. */
  programId: string
  /** Base58 oracle key to pass to `create_deal`. */
  oracle: string
  /** RPC URL of the cluster the monitor reports on. */
  rpcUrl: string
  /** Health-probe interval; deal rounds must match this sampling interval. */
  checkIntervalSeconds: number
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
