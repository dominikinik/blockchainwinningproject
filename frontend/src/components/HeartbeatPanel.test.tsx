import { act, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { heartbeatApi, type Heartbeat } from '../services/heartbeat/heartbeatApi'
import { HeartbeatPanel } from './HeartbeatPanel'

vi.mock('../services/heartbeat/heartbeatApi', () => ({ heartbeatApi: { getRecent: vi.fn() } }))
const getRecent = vi.mocked(heartbeatApi.getRecent)

const NOW = new Date('2026-01-01T00:00:10Z')
const ADDR = 'DealAddr1111111111111111111111111111111111'

function beat(id: number, overrides: Partial<Heartbeat> = {}): Heartbeat {
  return {
    id, dealAddress: ADDR, round: id - 1, checkedAt: new Date(NOW.getTime() - 3000 - (100 - id) * 1000).toISOString(),
    up: true, outcome: 'HEALTHY', httpStatus: 200, detail: null, latencyMs: 12, report: 'SENT', reportError: null, signature: 'sig',
    ...overrides,
  }
}

async function setup() {
  render(<MemoryRouter><HeartbeatPanel /></MemoryRouter>)
  await act(async () => {})
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
  getRecent.mockReset()
})
afterEach(() => { vi.useRealTimers() })

describe('HeartbeatPanel', () => {
  it('shows loading, then data', async () => {
    let resolve: (value: Heartbeat[]) => void = () => {}
    getRecent.mockReturnValue(new Promise((r) => { resolve = r }))
    render(<MemoryRouter><HeartbeatPanel /></MemoryRouter>)
    expect(screen.getByText('Oracle heartbeats')).toBeInTheDocument()
    expect(screen.getByText('Loading heartbeats...')).toBeInTheDocument()
    await act(async () => { resolve([beat(1)]) })
    expect(screen.queryByText('Loading heartbeats...')).not.toBeInTheDocument()
    expect(screen.getAllByTestId('heartbeat-row')).toHaveLength(1)
    expect(screen.getByText('Live')).toBeInTheDocument()
  })

  it('computes the summary stats', async () => {
    // newest first: id 4 (newest, 3s ago) down + dropped, 3 up + retrying, 2 up, 1 up
    getRecent.mockResolvedValue([
      beat(4, { up: false, outcome: 'DOWN', report: 'DROPPED', checkedAt: new Date(NOW.getTime() - 3000).toISOString() }),
      beat(3, { report: 'RETRYING' }), beat(2), beat(1),
    ])
    await setup()
    const stat = (label: string) => screen.getByText(label).nextElementSibling as HTMLElement
    expect(stat('Beats shown')).toHaveTextContent('4')
    expect(stat('UP rate')).toHaveTextContent('75%')
    expect(stat('Last beat')).toHaveTextContent('3s ago')
    expect(stat('Not sent')).toHaveTextContent('2')
  })

  it('draws the strip oldest to newest with colors and unsent outlines', async () => {
    getRecent.mockResolvedValue([beat(3, { up: false, outcome: 'DOWN' }), beat(2, { report: 'RETRYING' }), beat(1)])
    await setup()
    const bars = within(screen.getByTestId('heartbeat-strip')).getAllByTestId('heartbeat-bar')
    expect(bars).toHaveLength(3)
    expect(bars[0]).toHaveClass('up')
    expect(bars[0]).not.toHaveClass('unsent')
    expect(bars[1]).toHaveClass('up', 'unsent')
    expect(bars[2]).toHaveClass('down')
    expect(bars[2].getAttribute('title')).toContain('round 3')
    expect(bars[2].getAttribute('title')).toContain('DOWN')
  })

  it('caps the table at 12 rows while the strip shows all', async () => {
    getRecent.mockResolvedValue(Array.from({ length: 20 }, (_, i) => beat(20 - i)))
    await setup()
    expect(screen.getAllByTestId('heartbeat-row')).toHaveLength(12)
    expect(screen.getAllByTestId('heartbeat-bar')).toHaveLength(20)
  })

  it('renders row cells: round +1, em dash for null HTTP, latency, report badge with error title, deal link', async () => {
    getRecent.mockResolvedValue([beat(1, { round: 0, up: false, outcome: 'INTERNAL_ERROR', httpStatus: null, latencyMs: 250, report: 'DROPPED', reportError: 'rpc timeout', checkedAt: '2026-01-01T00:00:05Z' })])
    await setup()
    const row = screen.getByTestId('heartbeat-row')
    expect(within(row).getByText('00:00:05')).toBeInTheDocument()
    expect(within(row).getByText('#1')).toBeInTheDocument()
    expect(within(row).getByText('DOWN')).toBeInTheDocument()
    expect(within(row).getByText('—')).toBeInTheDocument()
    expect(within(row).getByText('250 ms')).toBeInTheDocument()
    expect(within(row).getByText('DROPPED')).toHaveAttribute('title', 'rpc timeout')
    expect(within(row).getByRole('link')).toHaveAttribute('href', `/deal?deal=${ADDR}`)
  })

  it('shows SENT badge and an HTTP status', async () => {
    getRecent.mockResolvedValue([beat(1, { httpStatus: 200 })])
    await setup()
    expect(screen.getByText('SENT')).toBeInTheDocument()
    expect(screen.getByText('200')).toBeInTheDocument()
  })

  it('shows the empty state with a link to /deal', async () => {
    getRecent.mockResolvedValue([])
    await setup()
    expect(screen.getByText('No heartbeats yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /open deals/i })).toHaveAttribute('href', '/deal')
    expect(screen.queryByTestId('heartbeat-strip')).not.toBeInTheDocument()
  })

  it('shows an error state when the first fetch fails', async () => {
    getRecent.mockRejectedValue(new Error('Heartbeat log returned 502.'))
    await setup()
    expect(screen.getByText('Heartbeat log returned 502.')).toBeInTheDocument()
    expect(screen.queryByTestId('heartbeat-row')).not.toBeInTheDocument()
  })

  it('polls every 2 s without flashing the loading state', async () => {
    getRecent.mockResolvedValueOnce([beat(1)]).mockResolvedValue([beat(2), beat(1)])
    await setup()
    expect(getRecent).toHaveBeenCalledWith(50)
    expect(screen.getAllByTestId('heartbeat-row')).toHaveLength(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    expect(getRecent).toHaveBeenCalledTimes(2)
    expect(screen.queryByText('Loading heartbeats...')).not.toBeInTheDocument()
    expect(screen.getAllByTestId('heartbeat-row')).toHaveLength(2)
  })

  it('keeps data and shows a stale note when a later poll fails, then recovers', async () => {
    getRecent.mockResolvedValueOnce([beat(1)]).mockRejectedValueOnce(new Error('boom')).mockResolvedValue([beat(1)])
    await setup()
    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    expect(screen.getByText(/Stale: could not refresh \(boom\)/)).toBeInTheDocument()
    expect(screen.getAllByTestId('heartbeat-row')).toHaveLength(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    expect(screen.queryByText(/Stale/)).not.toBeInTheDocument()
  })

  it('stops polling on unmount', async () => {
    getRecent.mockResolvedValue([beat(1)])
    render(<MemoryRouter><HeartbeatPanel /></MemoryRouter>).unmount()
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(getRecent).toHaveBeenCalledTimes(1)
  })
})
