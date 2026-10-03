export type UptimeServiceState = 'UP' | 'DOWN'

interface StateResponse {
  status: UptimeServiceState
}

export const uptimeService = {
  async getState(): Promise<UptimeServiceState> {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 4000)

    try {
      const response = await fetch('/api/application/state', {
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        signal: controller.signal,
      })
      if (!response.ok) throw new Error(`Uptime service returned ${response.status}.`)

      const data: unknown = await response.json()
      if (!isStateResponse(data)) throw new Error('Uptime service returned an unexpected response.')
      return data.status
    } finally {
      window.clearTimeout(timeout)
    }
  },
}

function isStateResponse(value: unknown): value is StateResponse {
  return typeof value === 'object' && value !== null && 'status' in value &&
    (value.status === 'UP' || value.status === 'DOWN')
}
