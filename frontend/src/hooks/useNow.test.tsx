import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useNow } from './useNow'

describe('useNow', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-01-01T00:00:00Z')) })
  afterEach(() => { vi.useRealTimers() })

  it('returns the current time and ticks every second', () => {
    const start = Date.now()
    const { result } = renderHook(() => useNow())
    expect(result.current).toBe(start)
    act(() => { vi.advanceTimersByTime(999) })
    expect(result.current).toBe(start)
    act(() => { vi.advanceTimersByTime(1) })
    expect(result.current).toBe(start + 1000)
    act(() => { vi.advanceTimersByTime(2000) })
    expect(result.current).toBe(start + 3000)
  })

  it('stops ticking after unmount', () => {
    const { unmount } = renderHook(() => useNow())
    expect(vi.getTimerCount()).toBe(1)
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})
