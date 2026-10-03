import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes, MemoryRouter } from 'react-router-dom'
import { render } from '@testing-library/react'
import { slaService } from '../services/solana/slaService'
import { makeSLA, PROVIDER } from '../test/utils'
import { DashboardPage } from './DashboardPage'

vi.mock('../services/solana/slaService', () => ({ slaService: { getSLAs: vi.fn() } }))
const getSLAs = vi.mocked(slaService.getSLAs)

function setup() {
  return render(
    <MemoryRouter>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/sla/:id" element={<p>details page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('DashboardPage', () => {
  beforeEach(() => { getSLAs.mockReset() })
  afterEach(() => { vi.useRealTimers() })

  it('shows a loading state first', async () => {
    getSLAs.mockReturnValue(new Promise(() => {}))
    setup()
    expect(screen.getByText('Loading agreements...')).toBeInTheDocument()
    expect(screen.queryByText('Your agreements')).not.toBeInTheDocument()
  })

  it('shows an error with retry that reloads', async () => {
    getSLAs.mockRejectedValueOnce(new Error('Load failed')).mockResolvedValueOnce([])
    setup()
    expect(await screen.findByText('Load failed')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(await screen.findByText('No agreements yet')).toBeInTheDocument()
    expect(screen.queryByText('Load failed')).not.toBeInTheDocument()
  })

  it('shows the empty state with zeroed metrics when there are no SLAs', async () => {
    getSLAs.mockResolvedValue([])
    setup()
    expect(await screen.findByText('No agreements yet')).toBeInTheDocument()
    expect(screen.getByText('0.00%')).toBeInTheDocument()
    expect(screen.getByText('0 total agreements')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /create sla/i }).length).toBeGreaterThan(0)
  })

  it('renders rows and aggregate metrics', async () => {
    const day = 86_400_000
    getSLAs.mockResolvedValue([
      makeSLA({ id: 'a', name: 'Alpha', customerPaymentSol: 10, providerGuaranteeSol: 2, currentUptime: 100 }),
      makeSLA({ id: 'b', name: 'Beta', customerPaymentSol: 5, providerGuaranteeSol: 1, currentUptime: 98, status: 'violated', endAt: new Date(Date.now() - day).toISOString() }),
      makeSLA({ id: 'c', name: 'Gamma', customerPaymentSol: 20, providerGuaranteeSol: 4, successfulChecks: 0, failedChecks: 0, status: 'pending', settlement: { state: 'pending' } }),
      makeSLA({ id: 'd', name: 'Delta', customerPaymentSol: 7, providerGuaranteeSol: 1, settlement: { state: 'settled' } }),
    ])
    setup()
    expect(await screen.findByRole('link', { name: 'Alpha' })).toHaveAttribute('href', '/sla/a')
    // active: a and c (b ended, d settled) -> 02; locked 10 + 20; violations 1
    expect(screen.getByText('02')).toBeInTheDocument()
    expect(screen.getByText('30 SOL')).toBeInTheDocument()
    expect(screen.getByText('01')).toBeInTheDocument()
    // average of measured SLAs (a, b, d): (100 + 98 + 99.95) / 3
    expect(screen.getByText('99.32%')).toBeInTheDocument()
    expect(screen.getByText('4 total agreements')).toBeInTheDocument()
    expect(screen.getAllByText('Ended')).toHaveLength(1)
    expect(screen.getAllByText('—')).toHaveLength(1)
    expect(screen.getAllByText('Violated').length).toBeGreaterThan(0)
    expect(screen.getAllByText(`${PROVIDER.slice(0, 4)}...${PROVIDER.slice(-4)}`)).toHaveLength(4)
  })

  it('counts short agreements down by the second and drops them from active when they end', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    getSLAs.mockResolvedValue([makeSLA({ id: 'short', customerPaymentSol: 3, providerGuaranteeSol: 1, endAt: '2026-01-01T00:00:30Z' })])
    setup()
    expect(await screen.findByText('30s')).toBeInTheDocument()
    expect(screen.getByText('3 SOL', { selector: '.metric-card strong' })).toBeInTheDocument()
    await act(async () => { await vi.advanceTimersByTimeAsync(29_000) })
    expect(screen.getByText('1s')).toBeInTheDocument()
    await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })
    expect(screen.getByText('Ended')).toBeInTheDocument()
    expect(screen.getByText('0 SOL', { selector: '.metric-card strong' })).toBeInTheDocument()
  })

  it('navigates to the SLA details when a row is clicked', async () => {
    getSLAs.mockResolvedValue([makeSLA({ id: 'abc', name: 'Row API' })])
    setup()
    await userEvent.click((await screen.findByText('https://api.test/health')).closest('tr')!)
    await waitFor(() => expect(screen.getByText('details page')).toBeInTheDocument())
  })
})
