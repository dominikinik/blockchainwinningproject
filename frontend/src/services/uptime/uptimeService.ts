export type UptimeServiceState = 'UP' | 'DOWN'

export interface UptimePoint {
  time: string
  down: boolean
}

interface StateResponse {
  status: UptimeServiceState
}

export const uptimeService = {
  async getState(): Promise<UptimeServiceState> {
    const data: unknown = await getJson('/api/application/state')
    if (!isStateResponse(data)) throw new Error('Uptime service returned an unexpected state response.')
    return data.status
  },

  async getHistory(): Promise<UptimePoint[]> {
    // The backend persists completed seconds. Skip the current and immediately
    // previous second so an unflushed sample is not drawn as downtime.
    const end = new Date(Math.floor(Date.now() / 1000) * 1000 - 2000)
    const start = new Date(end.getTime() - 299_000)
    const params = new URLSearchParams({ from: start.toISOString(), to: end.toISOString() })
    const data: unknown = await getJson(`/api/uptime?${params}`)
    if (!Array.isArray(data) || !data.every(isUptimePoint)) {
      throw new Error('Uptime service returned an unexpected history response.')
    }
    return data
  },
}

async function getJson(url: string): Promise<unknown> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 4000)

  try {
    const response = await fetch(url, {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal: controller.signal,
    })
    if (!response.ok) throw new Error(`Uptime service returned ${response.status}.`)
    return response.json() as Promise<unknown>
  } finally {
    window.clearTimeout(timeout)
  }
}

function isStateResponse(value: unknown): value is StateResponse {
  return typeof value === 'object' && value !== null && 'status' in value &&
    (value.status === 'UP' || value.status === 'DOWN')
}

function isUptimePoint(value: unknown): value is UptimePoint {
  return typeof value === 'object' && value !== null &&
    'time' in value && typeof value.time === 'string' && Number.isFinite(Date.parse(value.time)) &&
    'down' in value && typeof value.down === 'boolean'
}
