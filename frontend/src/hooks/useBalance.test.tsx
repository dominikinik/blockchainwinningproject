import { act, renderHook } from '@testing-library/react'
import type { Connection } from '@solana/web3.js'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useBalance } from './useBalance'

const ADDRESS = '8xF2mTtX4k9Pjrv7EQ58R8v5J74y2CVxq9HZ1jK31Qz'

describe('useBalance', () => {
  const getBalance = vi.fn()
  const connection = { getBalance } as unknown as Connection
  beforeEach(() => { vi.useFakeTimers(); getBalance.mockReset() })
  afterEach(() => { vi.useRealTimers() })

  it('reads the balance and polls it', async () => {
    getBalance.mockResolvedValueOnce(5).mockResolvedValueOnce(7)
    const { result } = renderHook(() => useBalance(connection, ADDRESS, 1000))
    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(result.current.lamports).toBe(5)
    expect(getBalance.mock.calls[0][0].toBase58()).toBe(ADDRESS)
    expect(getBalance.mock.calls[0][1]).toBe('confirmed')
    await act(() => vi.advanceTimersByTimeAsync(1000))
    expect(result.current.lamports).toBe(7)
  })

  it('is null without an address, for an invalid one, or when the read fails', async () => {
    const { result, rerender } = renderHook(({ address }) => useBalance(connection, address), { initialProps: { address: null as string | null } })
    await act(() => vi.advanceTimersByTimeAsync(5000))
    expect(result.current.lamports).toBeNull()
    expect(getBalance).not.toHaveBeenCalled()

    rerender({ address: 'not-an-address' })
    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(result.current.lamports).toBeNull()

    getBalance.mockRejectedValue(new Error('rpc down'))
    rerender({ address: ADDRESS })
    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(result.current.lamports).toBeNull()
  })
})
