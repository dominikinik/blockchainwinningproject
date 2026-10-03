import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { uptimeService } from './uptimeService'

const jsonResponse = (body: unknown, init: { ok?: boolean; status?: number } = {}) =>
  ({ ok: init.ok ?? true, status: init.status ?? 200, json: async () => body }) as Response

describe('uptimeService.getState', () => {
  const fetchMock = vi.fn()
  beforeEach(() => { vi.useFakeTimers(); vi.stubGlobal('fetch', fetchMock); fetchMock.mockReset() })
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

  it('requests the state endpoint with JSON accept, no-store and an abort signal', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 'UP' }))
    await uptimeService.getState()
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/application/state')
    expect(init.headers).toEqual({ Accept: 'application/json' })
    expect(init.cache).toBe('no-store')
    expect(init.signal).toBeInstanceOf(AbortSignal)
  })

  it('returns UP', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 'UP' }))
    await expect(uptimeService.getState()).resolves.toBe('UP')
  })

  it('returns DOWN', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 'DOWN', extra: 1 }))
    await expect(uptimeService.getState()).resolves.toBe('DOWN')
  })

  it('throws with the status code on non-ok responses', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}, { ok: false, status: 503 }))
    await expect(uptimeService.getState()).rejects.toThrow('Uptime service returned 503.')
  })

  it.each([
    ['null body', null],
    ['string body', 'UP'],
    ['missing status', {}],
    ['unknown status', { status: 'MAYBE' }],
    ['lowercase status', { status: 'up' }],
    ['non-string status', { status: 1 }],
  ])('throws on malformed payload: %s', async (_name, body) => {
    fetchMock.mockResolvedValue(jsonResponse(body))
    await expect(uptimeService.getState()).rejects.toThrow('Uptime service returned an unexpected state response.')
  })

  it('propagates network errors', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(uptimeService.getState()).rejects.toThrow('Failed to fetch')
  })

  it('propagates invalid JSON errors', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => { throw new SyntaxError('bad json') } })
    await expect(uptimeService.getState()).rejects.toThrow('bad json')
  })

  it('aborts after 4 seconds', async () => {
    fetchMock.mockImplementation((_url: string, init: RequestInit) => new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    }))
    const result = expect(uptimeService.getState()).rejects.toThrow('Aborted')
    await vi.advanceTimersByTimeAsync(3999)
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true)
    await result
  })

  it('clears the timeout after a response', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 'UP' }))
    await uptimeService.getState()
    expect(vi.getTimerCount()).toBe(0)
  })
})

describe('uptimeService.getHistory', () => {
  const fetchMock = vi.fn()
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-03T12:00:10.750Z'))
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
  })
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

  const points = [
    { time: '2026-10-03T12:00:00Z', down: false },
    { time: '2026-10-03T12:00:01Z', down: true },
  ]

  it('requests the last 300 completed seconds, skipping the current and previous second', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]))
    await uptimeService.getHistory()
    const url = new URL(fetchMock.mock.calls[0][0], 'http://localhost')
    expect(url.pathname).toBe('/api/uptime')
    expect(url.searchParams.get('to')).toBe('2026-10-03T12:00:08.000Z')
    expect(url.searchParams.get('from')).toBe('2026-10-03T11:55:09.000Z')
    expect(fetchMock.mock.calls[0][1].cache).toBe('no-store')
  })

  it('returns the recorded points', async () => {
    fetchMock.mockResolvedValue(jsonResponse(points))
    await expect(uptimeService.getHistory()).resolves.toEqual(points)
  })

  it('returns an empty history', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]))
    await expect(uptimeService.getHistory()).resolves.toEqual([])
  })

  it('throws with the status code on non-ok responses', async () => {
    fetchMock.mockResolvedValue(jsonResponse([], { ok: false, status: 500 }))
    await expect(uptimeService.getHistory()).rejects.toThrow('Uptime service returned 500.')
  })

  it.each([
    ['null body', null],
    ['object body', { time: '2026-10-03T12:00:00Z', down: false }],
    ['null point', [null]],
    ['missing time', [{ down: false }]],
    ['unparseable time', [{ time: 'yesterday', down: false }]],
    ['non-string time', [{ time: 1, down: false }]],
    ['missing down', [{ time: '2026-10-03T12:00:00Z' }]],
    ['non-boolean down', [{ time: '2026-10-03T12:00:00Z', down: 'false' }]],
    ['one bad point among good ones', [...points, { time: 'x', down: true }]],
  ])('throws on malformed payload: %s', async (_name, body) => {
    fetchMock.mockResolvedValue(jsonResponse(body))
    await expect(uptimeService.getHistory()).rejects.toThrow('Uptime service returned an unexpected history response.')
  })

  it('propagates network errors', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(uptimeService.getHistory()).rejects.toThrow('Failed to fetch')
  })
})
