import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { uptimeService, type UptimePoint } from '../services/uptime/uptimeService'
import { renderAt } from '../test/utils'
import { MonitoringPage } from './MonitoringPage'

vi.mock('../services/uptime/uptimeService', () => ({ uptimeService: { getState: vi.fn(), getHistory: vi.fn() } }))
const getState = vi.mocked(uptimeService.getState)
const getHistory = vi.mocked(uptimeService.getHistory)

const history = (pattern: string): UptimePoint[] => pattern.split('').map((c, i) => ({
  time: new Date(Date.UTC(2026, 9, 3, 12, 0, i)).toISOString(), down: c === 'x',
}))

describe('MonitoringPage', () => {
  beforeEach(() => { getState.mockReset(); getHistory.mockReset() })
  afterEach(() => { vi.useRealTimers() })

  it('shows loading states and disables refresh while loading', () => {
    getState.mockReturnValue(new Promise(() => {}))
    getHistory.mockReturnValue(new Promise(() => {}))
    renderAt(<MonitoringPage />)
    expect(screen.getByText('Checking backend')).toBeInTheDocument()
    expect(screen.getByText('Checking the service state...')).toBeInTheDocument()
    expect(screen.getByText('Loading uptime history...')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Refresh uptime data' })).toBeDisabled()
  })

  it('shows UP state, uptime stats and the timeline', async () => {
    getState.mockResolvedValue('UP')
    getHistory.mockResolvedValue(history('...x'))
    renderAt(<MonitoringPage />)
    expect(await screen.findByText('Uptime service UP')).toBeInTheDocument()
    expect(screen.getByText(/The service reports UP/)).toBeInTheDocument()
    expect(screen.getByText('UP')).toBeInTheDocument()
    expect(await screen.findByText('75.0%')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /3 up seconds and 1 down seconds/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Refresh uptime data' })).toBeEnabled()
  })

  it('shows DOWN state', async () => {
    getState.mockResolvedValue('DOWN')
    getHistory.mockResolvedValue(history('x'))
    renderAt(<MonitoringPage />)
    expect(await screen.findByText('Uptime service DOWN')).toBeInTheDocument()
    expect(screen.getByText(/The service reports DOWN/)).toBeInTheDocument()
    expect(await screen.findByText('0.0%')).toBeInTheDocument()
  })

  it('shows Backend unavailable when the state request fails, keeping the history', async () => {
    getState.mockRejectedValue(new Error('network'))
    getHistory.mockResolvedValue(history('..'))
    renderAt(<MonitoringPage />)
    expect(await screen.findByText('Backend unavailable')).toBeInTheDocument()
    expect(screen.getByText(/The service is unavailable/)).toBeInTheDocument()
    expect(screen.getByText('Unavailable')).toBeInTheDocument()
    expect(await screen.findByText('100.0%')).toBeInTheDocument()
  })

  it('shows a history error with dashes in the stats, and retry reloads both', async () => {
    getState.mockResolvedValue('UP')
    getHistory.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(history('.'))
    renderAt(<MonitoringPage />)
    expect(await screen.findByText('Could not load uptime history from the backend.')).toBeInTheDocument()
    expect(screen.getAllByText('—')).toHaveLength(2)
    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(await screen.findByText('100.0%')).toBeInTheDocument()
    expect(getState).toHaveBeenCalledTimes(2)
    expect(getHistory).toHaveBeenCalledTimes(2)
  })

  it('shows an empty-history message', async () => {
    getState.mockResolvedValue('UP')
    getHistory.mockResolvedValue([])
    renderAt(<MonitoringPage />)
    expect(await screen.findByText('No recorded seconds are available yet.')).toBeInTheDocument()
    expect(screen.getByText('0')).toBeInTheDocument()
  })

  it('refresh button reloads state and history', async () => {
    getState.mockRejectedValueOnce(new Error('x')).mockResolvedValueOnce('UP')
    getHistory.mockResolvedValue([])
    renderAt(<MonitoringPage />)
    await screen.findByText('Backend unavailable')
    await userEvent.click(screen.getByRole('button', { name: 'Refresh uptime data' }))
    expect(await screen.findByText('Uptime service UP')).toBeInTheDocument()
    expect(getState).toHaveBeenCalledTimes(2)
    expect(getHistory).toHaveBeenCalledTimes(2)
  })

  it('polls state and history every 10 seconds and stops after unmount', async () => {
    vi.useFakeTimers()
    getState.mockResolvedValue('UP')
    getHistory.mockResolvedValue([])
    const { unmount } = renderAt(<MonitoringPage />)
    await act(async () => { await vi.advanceTimersByTimeAsync(9_999) })
    expect(getState).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(getState).toHaveBeenCalledTimes(2)
    expect(getHistory).toHaveBeenCalledTimes(2)
    unmount()
    await vi.advanceTimersByTimeAsync(20_000)
    expect(getState).toHaveBeenCalledTimes(2)
  })
})
