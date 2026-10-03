import { describe, expect, it, vi } from 'vitest'
import { programErrorCode, withRetry } from './retry.js'
import { programError, rpcError } from './testutil.js'

describe('withRetry', () => {
  it('retries RPC errors with exponential, capped backoff', async () => {
    const sleep = vi.fn(async (_ms: number) => {})
    const fn = vi.fn().mockRejectedValueOnce(rpcError()).mockRejectedValueOnce(rpcError()).mockResolvedValue('ok')
    expect(await withRetry(fn, { attempts: 5, baseMs: 100, maxMs: 150, sleep })).toBe('ok')
    expect(sleep.mock.calls.map((c) => c[0])).toEqual([100, 150])
  })
  it('gives up after the bounded number of attempts', async () => {
    const fn = vi.fn().mockRejectedValue(rpcError())
    await expect(withRetry(fn, { attempts: 3, baseMs: 1, maxMs: 1, sleep: async () => {} })).rejects.toThrow('503')
    expect(fn).toHaveBeenCalledTimes(3)
  })
  it('does not retry program errors', async () => {
    const fn = vi.fn().mockRejectedValue(programError(6021))
    await expect(withRetry(fn, { attempts: 3, baseMs: 1, maxMs: 1, sleep: async () => {} })).rejects.toThrow()
    expect(fn).toHaveBeenCalledTimes(1)
  })
})

describe('programErrorCode', () => {
  it('reads Anchor errors, hex logs and decimal messages', () => {
    expect(programErrorCode({ error: { errorCode: { number: 6020 } } })).toBe(6020)
    expect(programErrorCode(programError(6021))).toBe(6021)
    expect(programErrorCode({ message: 'x', logs: ['Error Number: 6019. Error Message: ...'] })).toBe(6019)
    expect(programErrorCode(new Error('boom'))).toBeUndefined()
  })
})
