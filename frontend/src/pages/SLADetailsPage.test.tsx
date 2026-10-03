import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { slaService } from '../services/solana/slaService'
import { makeSLA, WALLET } from '../test/utils'
import type { Monitor, Observation, SLA } from '../types'
import { SLADetailsPage } from './SLADetailsPage'

const wallet = vi.hoisted(() => ({ publicKey: null as unknown, setVisible: vi.fn() }))
vi.mock('@solana/wallet-adapter-react', () => ({ useWallet: () => ({ publicKey: wallet.publicKey }) }))
vi.mock('@solana/wallet-adapter-react-ui', () => ({ useWalletModal: () => ({ setVisible: wallet.setVisible }) }))
vi.mock('../services/solana/slaService', () => ({
  slaService: { getSLA: vi.fn(), getObservations: vi.fn(), getMonitors: vi.fn(), settleSLA: vi.fn() },
}))
const svc = vi.mocked(slaService)

const monitors: Monitor[] = [{ id: 'a', name: 'Monitoring server', wallet: WALLET, status: 'online', observations: 1, agreementRate: 99, lastObservationAt: new Date().toISOString() }]
const observations: Observation[] = [
  { id: 'o1', slaId: 'test-sla', monitorId: 'a', timestamp: '2025-03-05T14:07:00Z', result: 'up', latencyMs: 120, transaction: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' },
  { id: 'o2', slaId: 'test-sla', monitorId: 'zz', timestamp: '2025-03-05T14:08:00Z', result: 'down', latencyMs: null, transaction: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' },
]

function load(sla: SLA | undefined, opts: { obs?: Observation[] } = {}) {
  svc.getSLA.mockResolvedValue(sla)
  svc.getObservations.mockResolvedValue(opts.obs ?? observations)
  svc.getMonitors.mockResolvedValue(monitors)
}
const settleButton = () => screen.findByRole('button', { name: /request settlement/i })
const setup = () => render(
  <MemoryRouter initialEntries={['/sla/test-sla']}>
    <Routes><Route path="/sla/:id" element={<SLADetailsPage />} /></Routes>
  </MemoryRouter>,
)

describe('SLADetailsPage', () => {
  afterEach(() => { vi.useRealTimers() })
  beforeEach(() => {
    Object.values(svc).forEach((fn) => fn.mockReset())
    wallet.publicKey = null
    wallet.setVisible.mockReset()
  })

  it('shows loading', () => {
    svc.getSLA.mockReturnValue(new Promise(() => {}))
    svc.getObservations.mockReturnValue(new Promise(() => {}))
    svc.getMonitors.mockReturnValue(new Promise(() => {}))
    setup()
    expect(screen.getByText('Loading agreement...')).toBeInTheDocument()
  })

  it('shows an error with retry', async () => {
    svc.getSLA.mockRejectedValueOnce(new Error('kaput'))
    svc.getObservations.mockResolvedValue([])
    svc.getMonitors.mockResolvedValue([])
    setup()
    expect(await screen.findByText('kaput')).toBeInTheDocument()
    svc.getSLA.mockResolvedValue(makeSLA())
    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(await screen.findByRole('heading', { name: 'Test API' })).toBeInTheDocument()
  })

  it('shows not found for a missing SLA', async () => {
    load(undefined)
    setup()
    expect(await screen.findByText('Agreement not found')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /back to dashboard/i })).toHaveAttribute('href', '/')
    expect(svc.getSLA).toHaveBeenCalledWith('test-sla')
  })

  it('renders SLA details and observations', async () => {
    load(makeSLA())
    setup()
    expect(await screen.findByRole('heading', { name: 'Test API' })).toBeInTheDocument()
    expect(screen.getByText('https://api.test/health')).toBeInTheDocument()
    expect(screen.getByText('99.95%', { selector: 'strong' })).toBeInTheDocument()
    expect(screen.getByText('Measured so far')).toBeInTheDocument()
    expect(screen.getByText('100 checks recorded')).toBeInTheDocument()
    expect(screen.getByText('7 days')).toBeInTheDocument()
    expect(screen.getByText('One server')).toBeInTheDocument()
    expect(screen.queryByText(/consensus/i)).not.toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Server' })).toBeInTheDocument()
    expect(screen.getByText('Timeout')).toBeInTheDocument()
    expect(screen.getByText('120ms')).toBeInTheDocument()
    expect(screen.getByText('Mar 5, 2025, 2:07 PM')).toBeInTheDocument()
    expect(screen.getByText('Monitoring server')).toBeInTheDocument()
    expect(screen.getByText('zz')).toBeInTheDocument() // unknown monitor falls back to id
    expect(screen.getByText('Latest 2')).toBeInTheDocument()
    expect(screen.getByText('DISPLAY PROJECTION')).toBeInTheDocument()
    expect(screen.getByText(/Settlement available after the end time/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /request settlement/i })).not.toBeInTheDocument()
  })

  it('shows start and end times with seconds for agreements shorter than an hour', async () => {
    load(makeSLA({ durationDays: 30 / 86_400, startAt: '2025-03-05T14:07:09Z', endAt: '2025-03-05T14:07:39Z' }))
    setup()
    expect(await screen.findByText('Mar 5, 2025, 2:07:09 PM')).toBeInTheDocument()
    expect(screen.getByText('Mar 5, 2025, 2:07:39 PM')).toBeInTheDocument()
    expect(screen.getByText('30 seconds')).toBeInTheDocument()
  })

  it('counts down every second and offers settlement once the end time passes', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    load(makeSLA({ endAt: '2026-01-01T00:01:30Z', settlement: { state: 'pending' } }))
    setup()
    expect(await screen.findByText('1m 30s')).toBeInTheDocument()
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    expect(screen.getByText('1m 29s')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /request settlement/i })).not.toBeInTheDocument()
    await act(async () => { await vi.advanceTimersByTimeAsync(89_000) })
    expect(screen.getByText('Ended')).toBeInTheDocument()
    expect(await settleButton()).toBeEnabled()
    expect(screen.queryByText(/Settlement available after the end time/)).not.toBeInTheDocument()
  })

  it('shows empty states for a fresh SLA with no data', async () => {
    load(makeSLA({ successfulChecks: 0, failedChecks: 0, timeline: [], settlement: { state: 'pending' } }), { obs: [] })
    setup()
    expect(await screen.findByText('Awaiting observations')).toBeInTheDocument()
    expect(screen.getAllByText('No observations yet')).toHaveLength(2)
    expect(screen.getByText(/A projection will appear/)).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('shows the settled result', async () => {
    load(makeSLA({ settlement: { state: 'settled', actualRecipient: 'customer', transaction: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' } }))
    setup()
    expect(await screen.findByText('Settlement request recorded')).toBeInTheDocument()
    expect(screen.getByText('Settlement request for 12 SOL is recorded.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /request settlement/i })).not.toBeInTheDocument()
    expect(screen.queryByText('DISPLAY PROJECTION')).not.toBeInTheDocument()
  })

  describe('settling a ready SLA', () => {
    const ready = () => makeSLA({ endAt: new Date(Date.now() - 1000).toISOString(), settlement: { state: 'ready', projectionRecipient: 'customer', projectionAmountSol: 120 } })

    it('opens the wallet modal when no wallet is connected', async () => {
      load(ready())
      setup()
      await userEvent.click(await settleButton())
      expect(wallet.setVisible).toHaveBeenCalledWith(true)
      expect(svc.settleSLA).not.toHaveBeenCalled()
    })

    it('settles and reloads when connected', async () => {
      wallet.publicKey = { toBase58: () => WALLET }
      load(ready())
      svc.settleSLA.mockResolvedValue(ready())
      setup()
      const button = await settleButton()
      svc.getSLA.mockResolvedValue(makeSLA({ settlement: { state: 'settled', actualRecipient: 'customer', transaction: 'tx' } }))
      await userEvent.click(button)
      expect(svc.settleSLA).toHaveBeenCalledWith('test-sla')
      expect(await screen.findByText('Settlement request recorded')).toBeInTheDocument()
    })

    it('shows the settle error', async () => {
      wallet.publicKey = { toBase58: () => WALLET }
      load(ready())
      svc.settleSLA.mockRejectedValue(new Error('This SLA has not ended yet.'))
      setup()
      await userEvent.click(await settleButton())
      expect(await screen.findByText('This SLA has not ended yet.')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /request settlement/i })).toBeEnabled()
    })

    it('falls back to a generic message for non-Error rejections', async () => {
      wallet.publicKey = { toBase58: () => WALLET }
      load(ready())
      svc.settleSLA.mockRejectedValue('x')
      setup()
      await userEvent.click(await settleButton())
      expect(await screen.findByText('Could not settle SLA.')).toBeInTheDocument()
    })
  })
})
