import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { fromHex, getBit } from './bitmap.js'
import { Monitor } from './monitor.js'
import { FileStateStore, MemoryStateStore, type StateStore } from './state.js'
import { ME, SLA_ADDR, makeSla, mockClient, programError, rpcError } from './testutil.js'

function setup(opts: { slas?: ReturnType<typeof makeSla>[]; store?: StateStore; status?: number } = {}) {
  const clock = { t: 1000 }
  const client = mockClient(opts.slas)
  const store = opts.store ?? new MemoryStateStore()
  const fetch = vi.fn(async () => ({ status: opts.status ?? 200 }))
  const mk = () =>
    new Monitor({
      client,
      store,
      fetch,
      now: () => clock.t,
      sleep: async () => {},
      self: ME,
      retry: { attempts: 3, baseMs: 1, maxMs: 1 },
    })
  return { clock, client, store, fetch, monitor: mk(), mk }
}

const bits = (hex: string, n = 8) => Array.from({ length: n }, (_, j) => getBit(fromHex(hex), j))

describe('checks and bitmaps', () => {
  it('checks each slot once and fills checked/up bitmaps', async () => {
    const { clock, monitor, store, fetch } = setup()
    await monitor.refresh()
    for (const t of [1000, 1010, 1025, 1050, 1075]) {
      clock.t = t
      await monitor.runChecks()
    }
    expect(fetch).toHaveBeenCalledTimes(4) // slots 0..3; the repeat at 1010 is skipped
    const e = store.get(SLA_ADDR, 0)!
    expect(bits(e.checked, 5)).toEqual([true, true, true, true, false])
    expect(bits(e.up, 5)).toEqual([true, true, true, true, false])
    expect(e).toMatchObject({ endTs: 1100, deadline: 1120 })
  })

  it('records a down slot as checked but not up (non-2xx)', async () => {
    const { monitor, store } = setup({ status: 503 })
    await monitor.refresh()
    await monitor.runChecks()
    const e = store.get(SLA_ADDR, 0)!
    expect(bits(e.checked, 2)).toEqual([true, false])
    expect(bits(e.up, 2)).toEqual([false, false])
  })

  it('records a network error as checked and down', async () => {
    const { monitor, store, fetch } = setup()
    fetch.mockRejectedValueOnce(new TypeError('fetch failed'))
    await monitor.refresh()
    await monitor.runChecks()
    const e = store.get(SLA_ADDR, 0)!
    expect(bits(e.checked, 1)).toEqual([true])
    expect(bits(e.up, 1)).toEqual([false])
  })

  it('records a timeout as checked and down', async () => {
    vi.useFakeTimers()
    try {
      const { monitor, store, fetch } = setup()
      fetch.mockImplementationOnce(() => new Promise(() => {}))
      await monitor.refresh()
      const run = monitor.runChecks()
      await vi.advanceTimersByTimeAsync(1000) // timeoutMs
      await run
      const e = store.get(SLA_ADDR, 0)!
      expect(bits(e.checked, 1)).toEqual([true])
      expect(bits(e.up, 1)).toEqual([false])
    } finally {
      vi.useRealTimers()
    }
  })

  it('keeps a missed slot unchecked (no catch-up checks)', async () => {
    const { clock, monitor, store } = setup()
    await monitor.refresh()
    clock.t = 1000
    await monitor.runChecks()
    clock.t = 1060 // slot 2 (1050) was missed; slot 2 is current at 1060
    await monitor.runChecks()
    expect(bits(store.get(SLA_ADDR, 0)!.checked, 4)).toEqual([true, false, true, false])
  })

  it('does not check before start, after end, or when settled', async () => {
    const s = setup({ slas: [makeSla(), makeSla({ address: 'Settled', settled: true })] })
    await s.monitor.refresh()
    s.clock.t = 999
    await s.monitor.runChecks()
    s.clock.t = 1250
    await s.monitor.runChecks()
    s.clock.t = 5000
    await s.monitor.runChecks()
    expect(s.fetch).not.toHaveBeenCalled()
    s.clock.t = 1000
    await s.monitor.runChecks()
    expect(s.fetch).toHaveBeenCalledTimes(1) // only the live SLA
  })

  it('checks the shorter last window slots only', async () => {
    const { clock, monitor, store } = setup()
    await monitor.refresh()
    for (const t of [1200, 1225, 1249]) {
      clock.t = t
      await monitor.runChecks()
    }
    expect(bits(store.get(SLA_ADDR, 2)!.checked, 3)).toEqual([true, true, false])
    expect(store.get(SLA_ADDR, 2)).toMatchObject({ endTs: 1250, deadline: 1270 })
  })
})

describe('reporting', () => {
  async function withWindow0() {
    const s = setup()
    await s.monitor.refresh()
    for (const t of [1000, 1025, 1050, 1075]) {
      s.clock.t = t
      await s.monitor.runChecks()
    }
    return s
  }

  it('does not submit before the window ends, submits in [end, end+G), then drops the entry', async () => {
    const s = await withWindow0()
    s.clock.t = 1099
    await s.monitor.runReports()
    expect(s.client.submitReport).not.toHaveBeenCalled()
    s.clock.t = 1100
    await s.monitor.runReports()
    expect(s.client.submitReport).toHaveBeenCalledTimes(1)
    const [sla, w, checked, up] = s.client.submitReport.mock.calls[0] as unknown as [string, number, Uint8Array, Uint8Array]
    expect([sla, w, checked[0], up[0]]).toEqual([SLA_ADDR, 0, 0b1111, 0b1111])
    expect(s.store.entries()).toEqual([])
  })

  it('drops an entry whose deadline passed without submitting', async () => {
    const s = await withWindow0()
    s.clock.t = 1120 // end + G, no longer accepted
    await s.monitor.runReports()
    expect(s.client.submitReport).not.toHaveBeenCalled()
    expect(s.store.entries()).toEqual([])
  })

  it('still reports a window of an SLA that has since ended or left the list', async () => {
    const s = await withWindow0()
    s.client.fetchAssignedSlas.mockResolvedValue([])
    await s.monitor.refresh()
    s.clock.t = 1100
    await s.monitor.runChecks()
    await s.monitor.runReports()
    expect(s.client.submitReport).toHaveBeenCalledTimes(1)
  })

  it('does not report windows it never checked', async () => {
    const s = setup()
    await s.monitor.refresh()
    s.clock.t = 1100
    await s.monitor.runReports()
    expect(s.client.submitReport).not.toHaveBeenCalled()
  })

  it('retries RPC errors then succeeds', async () => {
    const s = await withWindow0()
    s.client.submitReport.mockRejectedValueOnce(rpcError()).mockRejectedValueOnce(rpcError())
    s.clock.t = 1100
    await s.monitor.runReports()
    expect(s.client.submitReport).toHaveBeenCalledTimes(3)
    expect(s.store.entries()).toEqual([])
  })

  it('keeps the entry when RPC retries are exhausted, and resubmits next run', async () => {
    const s = await withWindow0()
    s.client.submitReport.mockRejectedValue(rpcError())
    s.clock.t = 1100
    await s.monitor.runReports()
    expect(s.client.submitReport).toHaveBeenCalledTimes(3)
    expect(s.store.entries()).toHaveLength(1)
    s.client.submitReport.mockResolvedValue(undefined)
    await s.monitor.runReports()
    expect(s.store.entries()).toEqual([])
  })

  it('treats DuplicateReport (6021) as success', async () => {
    const s = await withWindow0()
    s.client.submitReport.mockRejectedValue(programError(6021))
    s.clock.t = 1100
    await s.monitor.runReports()
    expect(s.client.submitReport).toHaveBeenCalledTimes(1) // no retry
    expect(s.store.entries()).toEqual([])
  })

  it('treats ReportDeadlinePassed (6020) as drop', async () => {
    const s = await withWindow0()
    s.client.submitReport.mockRejectedValue(programError(6020))
    s.clock.t = 1100
    await s.monitor.runReports()
    expect(s.store.entries()).toEqual([])
  })

  it('keeps the entry on other program errors (e.g. clock skew, WindowNotEnded)', async () => {
    const s = await withWindow0()
    s.client.submitReport.mockRejectedValue(programError(6019))
    s.clock.t = 1100
    await s.monitor.runReports()
    expect(s.client.submitReport).toHaveBeenCalledTimes(1)
    expect(s.store.entries()).toHaveLength(1)
  })
})

describe('restart recovery', () => {
  it('reloads unsent bitmaps from the state file and does not redo checked slots', async () => {
    const path = join(mkdtempSync(join(tmpdir(), 'sla-monitor-')), 'state.json')
    const a = setup({ store: new FileStateStore(path) })
    await a.monitor.refresh()
    await a.monitor.runChecks() // slot 0 at t=1000, then the process "crashes"

    const b = setup({ store: new FileStateStore(path) })
    b.clock.t = 1000
    await b.monitor.refresh()
    await b.monitor.runChecks()
    expect(b.fetch).not.toHaveBeenCalled() // slot 0 already in the file
    b.clock.t = 1025
    await b.monitor.runChecks()
    expect(b.fetch).toHaveBeenCalledTimes(1)

    b.clock.t = 1100
    await b.monitor.runReports()
    const call = b.client.submitReport.mock.calls[0] as unknown as [string, number, Uint8Array, Uint8Array]
    expect(call[2][0]).toBe(0b11)
    expect(new FileStateStore(path).entries()).toEqual([])
  })

  it('drops entries past their deadline after a long outage', async () => {
    const path = join(mkdtempSync(join(tmpdir(), 'sla-monitor-')), 'state.json')
    const a = setup({ store: new FileStateStore(path) })
    await a.monitor.refresh()
    await a.monitor.runChecks()
    const b = setup({ store: new FileStateStore(path) })
    b.clock.t = 9999
    await b.monitor.runReports()
    expect(b.client.submitReport).not.toHaveBeenCalled()
    expect(new FileStateStore(path).entries()).toEqual([])
  })
})

describe('refresh', () => {
  it('keeps the previous SLA list when the RPC keeps failing', async () => {
    const s = setup()
    await s.monitor.refresh()
    s.client.fetchAssignedSlas.mockRejectedValue(rpcError())
    await s.monitor.refresh()
    expect(s.monitor.assigned).toHaveLength(1)
    expect(s.client.fetchAssignedSlas).toHaveBeenCalledTimes(4) // 1 + 3 attempts
  })
})

describe('finalize', () => {
  it('waits for end + G, strictly for the next window', async () => {
    const s = setup()
    await s.monitor.refresh()
    s.clock.t = 1119
    await s.monitor.runFinalize()
    expect(s.client.finalizeWindow).not.toHaveBeenCalled()
    s.clock.t = 1120
    await s.monitor.runFinalize()
    expect(s.client.finalizeWindow).toHaveBeenCalledTimes(1)
    expect(s.client.finalizeWindow).toHaveBeenCalledWith(SLA_ADDR, 0, ME, makeSla().monitors)
  })

  it('finalizes overdue windows in order, with the shorter last window deadline', async () => {
    const s = setup()
    await s.monitor.refresh()
    s.clock.t = 1269 // window 2 ends 1250, deadline 1270
    await s.monitor.runFinalize()
    expect(s.client.finalizeWindow.mock.calls.map((c) => (c as unknown[])[1])).toEqual([0, 1])
    s.clock.t = 1270
    await s.monitor.runFinalize()
    expect(s.client.finalizeWindow.mock.calls.map((c) => (c as unknown[])[1])).toEqual([0, 1, 2])
  })

  it('starts from sla.next_window_to_finalize and stops at total_windows', async () => {
    const s = setup({ slas: [makeSla({ nextWindowToFinalize: 2 })] })
    await s.monitor.refresh()
    s.clock.t = 9999
    await s.monitor.runFinalize()
    expect(s.client.finalizeWindow).toHaveBeenCalledTimes(1)
    expect((s.client.finalizeWindow.mock.calls[0] as unknown[])[1]).toBe(2)
  })

  it('skips settled and fully finalized SLAs', async () => {
    const s = setup({ slas: [makeSla({ settled: true }), makeSla({ address: 'Done', nextWindowToFinalize: 3 })] })
    await s.monitor.refresh()
    s.clock.t = 9999
    await s.monitor.runFinalize()
    expect(s.client.finalizeWindow).not.toHaveBeenCalled()
  })

  it('uses WindowReport.payer when the report exists, else self', async () => {
    const s = setup()
    s.client.fetchWindowReportPayer.mockResolvedValueOnce('ReporterPayer')
    await s.monitor.refresh()
    s.clock.t = 1220
    await s.monitor.runFinalize()
    const payers = s.client.finalizeWindow.mock.calls.map((c) => (c as unknown[])[2])
    expect(payers).toEqual(['ReporterPayer', ME])
  })

  it('does not skip ahead when a window fails to finalize', async () => {
    const s = setup()
    s.client.finalizeWindow.mockRejectedValue(programError(6023))
    await s.monitor.refresh()
    s.clock.t = 9999
    await s.monitor.runFinalize()
    expect(s.client.finalizeWindow).toHaveBeenCalledTimes(1)
  })

  it('retries RPC errors when finalizing', async () => {
    const s = setup()
    s.client.finalizeWindow.mockRejectedValueOnce(rpcError())
    await s.monitor.refresh()
    s.clock.t = 1120
    await s.monitor.runFinalize()
    expect(s.client.finalizeWindow).toHaveBeenCalledTimes(2)
  })
})
