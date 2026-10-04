import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { heartbeatApi } from './heartbeatApi'

const fetchMock = vi.fn()
beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock) })
afterEach(() => { vi.unstubAllGlobals() })

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

describe('heartbeatApi', () => {
  it('getRecent defaults the limit to 50 and disables caching', async () => {
    fetchMock.mockResolvedValue(json([{ id: 1 }]))
    expect(await heartbeatApi.getRecent()).toEqual([{ id: 1 }])
    expect(fetchMock).toHaveBeenCalledWith('/api/heartbeats?limit=50', expect.objectContaining({ cache: 'no-store' }))
  })

  it('getRecent passes a custom limit', async () => {
    fetchMock.mockResolvedValue(json([]))
    await heartbeatApi.getRecent(7)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/heartbeats?limit=7')
  })

  it('getForDeal encodes the address', async () => {
    fetchMock.mockResolvedValue(json([]))
    await heartbeatApi.getForDeal('a/b c')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/deals/a%2Fb%20c/heartbeats')
  })

  it('throws the problem detail', async () => {
    fetchMock.mockResolvedValue(json({ detail: 'Deal not found' }, 404))
    await expect(heartbeatApi.getForDeal('x')).rejects.toThrow('Deal not found')
  })

  it('falls back to a status message for non-JSON errors', async () => {
    fetchMock.mockResolvedValue(new Response('<html>oops</html>', { status: 502 }))
    await expect(heartbeatApi.getRecent()).rejects.toThrow('Heartbeat log returned 502.')
  })

  it('falls back when the problem has no string detail', async () => {
    fetchMock.mockResolvedValue(json({ detail: 5 }, 500))
    await expect(heartbeatApi.getRecent()).rejects.toThrow('Heartbeat log returned 500.')
  })
})
