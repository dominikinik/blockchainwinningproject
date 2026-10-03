import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockConsensus, mockMonitors, mockObservations, mockSLAs } from '../../mocks/data'
import type { CreateSLAInput, SLA } from '../../types'
import { slaService } from './slaService'

const KEY = 'slana.mock.slas.v1'
const input: CreateSLAInput = {
  name: 'New API', endpoint: 'https://new.example.com', customerWallet: 'cust', providerWallet: 'prov',
  escrowSol: 3, requiredUptime: 99, durationDays: 2, checkIntervalMinutes: 5, timeoutMs: 1000,
  consensusRequired: 3, monitorCount: 5,
}
const stored = () => JSON.parse(window.localStorage.getItem(KEY) || '[]') as SLA[]
// Start a call, advance fake time by its delay, and return its result.
async function run<T>(promise: Promise<T>, ms: number) {
  const settled = promise.then((value) => ({ value }), (error: unknown) => ({ error }))
  await vi.advanceTimersByTimeAsync(ms)
  return settled
}
const ENDED = 'email-api'

describe('slaService', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  it('waits before resolving reads', async () => {
    let done = false
    void slaService.getSLAs().then(() => { done = true })
    await vi.advanceTimersByTimeAsync(279)
    expect(done).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(done).toBe(true)
  })

  describe('reads', () => {
    it('getSLAs returns seeds when storage is empty', async () => {
      expect(await run(slaService.getSLAs(), 280)).toEqual({ value: mockSLAs })
    })
    it('getSLAs returns [] fallback on corrupt storage (seeds only)', async () => {
      window.localStorage.setItem(KEY, '{not json')
      expect(await run(slaService.getSLAs(), 280)).toEqual({ value: mockSLAs })
    })
    it('getSLAs merges stored overrides over seeds and puts new stored SLAs first', async () => {
      const override = { ...mockSLAs[0], name: 'Overridden' }
      const extra = { ...mockSLAs[0], id: 'custom', name: 'Custom' }
      window.localStorage.setItem(KEY, JSON.stringify([extra, override]))
      const result = (await run(slaService.getSLAs(), 280)) as { value: SLA[] }
      expect(result.value.map((s) => s.id)).toEqual(['custom', ...mockSLAs.map((s) => s.id)])
      expect(result.value.find((s) => s.id === mockSLAs[0].id)?.name).toBe('Overridden')
      expect(result.value.find((s) => s.id === mockSLAs[1].id)).toEqual(mockSLAs[1])
    })
    it('marks pending SLAs as ready once their end time has passed', async () => {
      const ended: SLA = { ...mockSLAs[0], id: 'ended', endAt: new Date(Date.now() - 1).toISOString(), settlement: { state: 'pending' } }
      const settled: SLA = { ...ended, id: 'settled', settlement: { state: 'settled', actualRecipient: 'provider' } }
      const active: SLA = { ...ended, id: 'active', endAt: new Date(Date.now() + 60_000).toISOString() }
      window.localStorage.setItem(KEY, JSON.stringify([ended, settled, active]))
      const result = (await run(slaService.getSLAs(), 280)) as { value: SLA[] }
      const state = (id: string) => result.value.find((s) => s.id === id)?.settlement.state
      expect([state('ended'), state('settled'), state('active')]).toEqual(['ready', 'settled', 'pending'])
      expect(stored()[0].settlement.state).toBe('pending')
      await vi.advanceTimersByTimeAsync(60_000)
      expect(((await run(slaService.getSLA('active'), 280)) as { value: SLA }).value.settlement.state).toBe('ready')
    })
    it('getSLA finds seeded, stored and missing ids', async () => {
      expect(await run(slaService.getSLA('payments-api'), 280)).toEqual({ value: mockSLAs[0] })
      window.localStorage.setItem(KEY, JSON.stringify([{ ...mockSLAs[0], id: 'custom' }]))
      expect(((await run(slaService.getSLA('custom'), 280)) as { value: SLA }).value.id).toBe('custom')
      expect(await run(slaService.getSLA('nope'), 280)).toEqual({ value: undefined })
    })
    it('getMonitors returns the fixtures', async () => {
      expect(await run(slaService.getMonitors(), 280)).toEqual({ value: mockMonitors })
    })
    it('getObservations filters by SLA id', async () => {
      const id = mockObservations[0].slaId
      const result = (await run(slaService.getObservations(id), 280)) as { value: typeof mockObservations }
      expect(result.value.length).toBeGreaterThan(0)
      expect(result.value.every((o) => o.slaId === id)).toBe(true)
      expect(await run(slaService.getObservations('nope'), 280)).toEqual({ value: [] })
    })
    it('getConsensus returns a snapshot or undefined', async () => {
      const id = Object.keys(mockConsensus)[0]
      expect(await run(slaService.getConsensus(id), 280)).toEqual({ value: mockConsensus[id] })
      expect(await run(slaService.getConsensus('nope'), 280)).toEqual({ value: undefined })
    })
  })

  describe('createSLA', () => {
    it('persists a pending SLA with generated fields after 550ms', async () => {
      vi.setSystemTime(new Date('2025-01-01T00:00:00Z'))
      let done = false
      const p = slaService.createSLA(input).then((v) => { done = true; return v })
      await vi.advanceTimersByTimeAsync(549)
      expect(done).toBe(false)
      expect(stored()).toEqual([])
      await vi.advanceTimersByTimeAsync(1)
      const sla = await p
      expect(sla).toMatchObject({
        ...input, status: 'pending', currentUptime: 0, successfulChecks: 0, failedChecks: 0,
        history: [], timeline: [], settlement: { state: 'pending' },
        startAt: '2025-01-01T00:00:00.550Z', endAt: '2025-01-03T00:00:00.550Z',
      })
      expect(sla.id).toMatch(/^sla-/)
      expect(stored()).toEqual([sla])
    })
    it('rounds fractional durations to whole milliseconds', async () => {
      vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
      const r = (await run(slaService.createSLA({ ...input, durationDays: 30 / 86_400 }), 550)) as { value: SLA }
      expect(r.value.endAt).toBe('2026-01-01T00:00:30.550Z')
      expect(new Date(r.value.endAt).getTime() - new Date(r.value.startAt).getTime()).toBe(30_000)
    })
    it('prepends to existing stored SLAs and generates unique ids', async () => {
      const a = (await run(slaService.createSLA(input), 550)) as { value: SLA }
      const b = (await run(slaService.createSLA(input), 550)) as { value: SLA }
      expect(a.value.id).not.toBe(b.value.id)
      expect(stored().map((s) => s.id)).toEqual([b.value.id, a.value.id])
    })
    it('is returned by getSLAs and getSLA', async () => {
      const created = ((await run(slaService.createSLA(input), 550)) as { value: SLA }).value
      expect(((await run(slaService.getSLAs(), 280)) as { value: SLA[] }).value[0].id).toBe(created.id)
      expect(((await run(slaService.getSLA(created.id), 280)) as { value: SLA }).value.name).toBe('New API')
    })
    it('recovers from corrupt storage', async () => {
      window.localStorage.setItem(KEY, 'garbage')
      const created = ((await run(slaService.createSLA(input), 550)) as { value: SLA }).value
      expect(stored()).toEqual([created])
    })
  })

  describe('settleSLA', () => {
    it('rejects unknown ids', async () => {
      const r = (await run(slaService.settleSLA('nope'), 750)) as { error: Error }
      expect(r.error.message).toBe('SLA not found.')
    })
    it('rejects SLAs that have not ended', async () => {
      const r = (await run(slaService.settleSLA('payments-api'), 750)) as { error: Error }
      expect(r.error.message).toBe('This SLA has not ended yet.')
      expect(stored()).toEqual([])
    })
    it('records a request for ended SLAs without a mock settlement result, leaving recipient and transaction unset', async () => {
      const ended: SLA = { ...mockSLAs[0], id: 'ended-custom', endAt: new Date(Date.now() - 1000).toISOString() }
      window.localStorage.setItem(KEY, JSON.stringify([ended]))
      const r = (await run(slaService.settleSLA('ended-custom'), 750)) as { value: SLA }
      expect(r.value.settlement.state).toBe('settled')
      expect(r.value.settlement.actualRecipient).toBeUndefined()
      expect(r.value.settlement.transaction).toBeUndefined()
      expect(r.value.settlement.settledAt).toBeTruthy()
    })
    it('settles an ended SLA and persists the result', async () => {
      vi.setSystemTime(new Date())
      const r = (await run(slaService.settleSLA(ENDED), 750)) as { value: SLA }
      expect(r.value.status).toBe('completed')
      expect(r.value.settlement).toMatchObject({ state: 'settled', actualRecipient: 'customer' })
      expect(r.value.settlement.transaction).toBeTruthy()
      expect(r.value.settlement.settledAt).toBeTruthy()
      expect(r.value.settlement.projectionRecipient).toBe('customer')
      expect(stored()).toEqual([r.value])
      const again = (await run(slaService.getSLA(ENDED), 280)) as { value: SLA }
      expect(again.value.settlement.state).toBe('settled')
    })
    it('returns an already-settled SLA unchanged without writing', async () => {
      const settled: SLA = { ...mockSLAs[2], settlement: { state: 'settled', actualRecipient: 'provider', transaction: 'tx' } }
      window.localStorage.setItem(KEY, JSON.stringify([settled]))
      const before = window.localStorage.getItem(KEY)
      const r = (await run(slaService.settleSLA(ENDED), 750)) as { value: SLA }
      expect(r.value).toEqual(settled)
      expect(window.localStorage.getItem(KEY)).toBe(before)
    })
    it('settles with corrupt storage falling back to seeds', async () => {
      window.localStorage.setItem(KEY, '%%%')
      const r = (await run(slaService.settleSLA(ENDED), 750)) as { value: SLA }
      expect(r.value.settlement.state).toBe('settled')
    })
  })
})
