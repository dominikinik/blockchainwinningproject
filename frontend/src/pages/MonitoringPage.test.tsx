import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { slaService } from '../services/solana/slaService'
import { uptimeService } from '../services/uptime/uptimeService'
import { renderAt } from '../test/utils'
import type { Monitor } from '../types'
import { MonitoringPage } from './MonitoringPage'

vi.mock('../services/solana/slaService', () => ({ slaService: { getMonitors: vi.fn() } }))
vi.mock('../services/uptime/uptimeService', () => ({ uptimeService: { getState: vi.fn() } }))
const getMonitors = vi.mocked(slaService.getMonitors)
const getState = vi.mocked(uptimeService.getState)

const monitor = (id: string, over: Partial<Monitor> = {}): Monitor => ({
  id, name: `Monitor ${id.toUpperCase()}`, wallet: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', status: 'online',
  observations: 1000, agreementRate: 99, lastObservationAt: new Date(Date.now() - 30_000).toISOString(), ...over,
})

describe('MonitoringPage', () => {
  beforeEach(() => { getMonitors.mockReset(); getState.mockReset() })

  it('shows loading states for monitors and backend', () => {
    getMonitors.mockReturnValue(new Promise(() => {}))
    getState.mockReturnValue(new Promise(() => {}))
    renderAt(<MonitoringPage />)
    expect(screen.getByText('Loading monitors...')).toBeInTheDocument()
    expect(screen.getByText('Checking backend')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Refresh uptime service state' })).toBeDisabled()
  })

  it('shows monitors, stats and Uptime service UP', async () => {
    getMonitors.mockResolvedValue([monitor('a'), monitor('b', { status: 'offline', observations: 2000, agreementRate: 97 })])
    getState.mockResolvedValue('UP')
    renderAt(<MonitoringPage />)
    expect(await screen.findByText('Uptime service UP')).toBeInTheDocument()
    expect(screen.getByText(/The service reports UP/)).toBeInTheDocument()
    expect(screen.getByText('01 / 02')).toBeInTheDocument()
    expect(screen.getByText('3,000')).toBeInTheDocument()
    expect(screen.getByText('98.0%')).toBeInTheDocument()
    expect(screen.getByText('Monitor A')).toBeInTheDocument()
    expect(screen.getByText('Offline')).toBeInTheDocument()
    expect(screen.getAllByText('ABCD...WXYZ')).toHaveLength(2)
    expect(screen.getAllByText('30 sec ago')).toHaveLength(2)
    expect(screen.getByText('How monitoring works')).toBeInTheDocument()
  })

  it('shows DOWN state', async () => {
    getMonitors.mockResolvedValue([monitor('a')])
    getState.mockResolvedValue('DOWN')
    renderAt(<MonitoringPage />)
    expect(await screen.findByText('Uptime service DOWN')).toBeInTheDocument()
    expect(screen.getByText(/The service reports DOWN/)).toBeInTheDocument()
  })

  it('shows Backend unavailable when the uptime service rejects, keeping monitors visible', async () => {
    getMonitors.mockResolvedValue([monitor('a')])
    getState.mockRejectedValue(new Error('network'))
    renderAt(<MonitoringPage />)
    expect(await screen.findByText('Backend unavailable')).toBeInTheDocument()
    expect(screen.getByText(/The backend is unavailable/)).toBeInTheDocument()
    expect(await screen.findByText('Monitor A')).toBeInTheDocument()
  })

  it('refresh button reloads backend state', async () => {
    getMonitors.mockResolvedValue([monitor('a')])
    getState.mockRejectedValueOnce(new Error('x')).mockResolvedValueOnce('UP')
    renderAt(<MonitoringPage />)
    await screen.findByText('Backend unavailable')
    await userEvent.click(screen.getByRole('button', { name: 'Refresh uptime service state' }))
    expect(await screen.findByText('Uptime service UP')).toBeInTheDocument()
    expect(getState).toHaveBeenCalledTimes(2)
  })

  it('shows an error with retry when monitors fail to load', async () => {
    getMonitors.mockRejectedValueOnce(new Error('monitors down')).mockResolvedValueOnce([monitor('a')])
    getState.mockResolvedValue('UP')
    renderAt(<MonitoringPage />)
    expect(await screen.findByText('monitors down')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(await screen.findByText('Monitor A')).toBeInTheDocument()
  })

  it('shows empty state with zero stats when there are no monitors', async () => {
    getMonitors.mockResolvedValue([])
    getState.mockResolvedValue('UP')
    renderAt(<MonitoringPage />)
    expect(await screen.findByText('No monitors registered')).toBeInTheDocument()
    expect(screen.getByText('00 / 00')).toBeInTheDocument()
    expect(screen.getByText('0.0%')).toBeInTheDocument()
  })
})
