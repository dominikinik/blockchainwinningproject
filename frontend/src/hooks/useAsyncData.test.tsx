import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useAsyncData } from './useAsyncData'

describe('useAsyncData', () => {
  it('starts loading then exposes data', async () => {
    const loader = vi.fn().mockResolvedValue('hello')
    const { result } = renderHook(() => useAsyncData(loader, []))
    expect(result.current).toMatchObject({ data: null, loading: true, error: null })
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current).toMatchObject({ data: 'hello', error: null })
    expect(loader).toHaveBeenCalledTimes(1)
  })

  it('exposes the message of Error rejections', async () => {
    const { result } = renderHook(() => useAsyncData(() => Promise.reject(new Error('boom')), []))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current).toMatchObject({ data: null, error: 'boom' })
  })

  it('uses a generic message for non-Error rejections', async () => {
    const { result } = renderHook(() => useAsyncData(() => Promise.reject('nope'), []))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('Something went wrong. Please try again.')
  })

  it('reload re-runs the loader, clears the error and updates data', async () => {
    const loader = vi.fn().mockRejectedValueOnce(new Error('first')).mockResolvedValueOnce(42)
    const { result } = renderHook(() => useAsyncData(loader, []))
    await waitFor(() => expect(result.current.error).toBe('first'))
    await act(async () => { await result.current.reload() })
    expect(result.current).toMatchObject({ data: 42, error: null, loading: false })
    expect(loader).toHaveBeenCalledTimes(2)
  })

  it('keeps previous data while a reload is in flight', async () => {
    let resolve!: (v: number) => void
    const loader = vi.fn().mockResolvedValueOnce(1).mockImplementationOnce(() => new Promise<number>((r) => { resolve = r }))
    const { result } = renderHook(() => useAsyncData(loader, []))
    await waitFor(() => expect(result.current.data).toBe(1))
    let pending!: Promise<void>
    act(() => { pending = result.current.reload() })
    expect(result.current).toMatchObject({ loading: true, data: 1 })
    await act(async () => { resolve(2); await pending })
    expect(result.current.data).toBe(2)
  })

  it('reloads when dependencies change but not when they stay the same', async () => {
    const loader = vi.fn((id: number) => Promise.resolve(id * 10))
    const { result, rerender } = renderHook(({ id }) => useAsyncData(() => loader(id), [id]), { initialProps: { id: 1 } })
    await waitFor(() => expect(result.current.data).toBe(10))
    rerender({ id: 1 })
    expect(loader).toHaveBeenCalledTimes(1)
    rerender({ id: 2 })
    await waitFor(() => expect(result.current.data).toBe(20))
    expect(loader).toHaveBeenCalledTimes(2)
  })
})
