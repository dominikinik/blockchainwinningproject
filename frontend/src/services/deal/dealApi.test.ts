import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { dealApi } from './dealApi'

const jsonResponse = (body: unknown, status = 200) =>
  ({ ok: status < 400, status, json: async () => body }) as Response

describe('dealApi', () => {
  const fetchMock = vi.fn()
  beforeEach(() => { vi.stubGlobal('fetch', fetchMock); fetchMock.mockReset() })
  afterEach(() => { vi.unstubAllGlobals() })

  it('reads the config', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ programId: 'P', oracle: 'O', rpcUrl: 'R', checkIntervalSeconds: 2 }))
    await expect(dealApi.getConfig()).resolves.toEqual({ programId: 'P', oracle: 'O', rpcUrl: 'R', checkIntervalSeconds: 2 })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/deals/config')
    expect(init.headers).toEqual({ Accept: 'application/json' })
    expect(init.cache).toBe('no-store')
  })

  it('registers a deal by address only', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ address: 'D', status: 'ACTIVE' }, 201))
    await expect(dealApi.register('D')).resolves.toMatchObject({ address: 'D', status: 'ACTIVE' })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/deals')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({ address: 'D' })
    expect(init.headers['Content-Type']).toBe('application/json')
  })

  it('reads one deal by its encoded address', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ address: 'a/b' }))
    await dealApi.get('a/b')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/deals/a%2Fb')
  })

  it('switches the service state', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ status: 'DOWN' })).mockResolvedValueOnce(jsonResponse({ status: 'UP' }))
    await expect(dealApi.setServiceUp(false)).resolves.toBe('DOWN')
    await expect(dealApi.setServiceUp(true)).resolves.toBe('UP')
    expect(fetchMock.mock.calls.map(([url, init]) => [url, init.method])).toEqual([
      ['/api/application/stop', 'POST'], ['/api/application/start', 'POST'],
    ])
  })

  it('surfaces the problem detail of errors', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ detail: 'Deal names oracle X' }, 400))
    await expect(dealApi.register('D')).rejects.toThrow('Deal names oracle X')
  })

  it('falls back to the status code when the error has no detail', async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 502, json: async () => { throw new SyntaxError('html') } } as unknown as Response)
    await expect(dealApi.get('D')).rejects.toThrow('Uptime service returned 502.')
    fetchMock.mockResolvedValueOnce(jsonResponse({ title: 'Not Found' }, 404))
    await expect(dealApi.get('D')).rejects.toThrow('Uptime service returned 404.')
  })
})
