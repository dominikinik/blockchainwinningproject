import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { checkEndpoint, type FetchFn } from './checker.js'

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('checkEndpoint', () => {
  it.each([200, 204, 299])('treats %i as up', async (status) => {
    expect(await checkEndpoint(async () => ({ status }), 'https://x', 1000)).toBe(true)
  })
  it.each([199, 301, 404, 500])('treats %i as down', async (status) => {
    expect(await checkEndpoint(async () => ({ status }), 'https://x', 1000)).toBe(false)
  })
  it('treats a network error as down', async () => {
    const f: FetchFn = async () => {
      throw new TypeError('fetch failed')
    }
    expect(await checkEndpoint(f, 'https://x', 1000)).toBe(false)
  })
  it('treats a timeout as down and aborts the request', async () => {
    let signal: AbortSignal | undefined
    const f: FetchFn = (_u, init) => {
      signal = init?.signal
      return new Promise(() => {}) // hangs, ignoring abort
    }
    const p = checkEndpoint(f, 'https://x', 500)
    await vi.advanceTimersByTimeAsync(499)
    expect(signal?.aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(await p).toBe(false)
    expect(signal?.aborted).toBe(true)
  })
})
