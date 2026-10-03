import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { slaService } from './services/solana/slaService'
import { uptimeService } from './services/uptime/uptimeService'

vi.mock('@solana/wallet-adapter-react', () => ({ useWallet: () => ({ publicKey: null }), useConnection: () => ({ connection: {} }) }))
vi.mock('@solana/wallet-adapter-react-ui', () => ({
  WalletMultiButton: () => <button>Wallet</button>,
  useWalletModal: () => ({ setVisible: vi.fn() }),
}))
vi.mock('./services/solana/slaService', () => ({
  slaService: { getSLAs: vi.fn(), getSLA: vi.fn(), getObservations: vi.fn(), getConsensus: vi.fn(), getMonitors: vi.fn() },
}))
vi.mock('./services/uptime/uptimeService', () => ({ uptimeService: { getState: vi.fn() } }))
vi.mock('./services/deal/dealApi', () => ({ dealApi: { getConfig: vi.fn().mockResolvedValue({ programId: 'P', oracle: 'O', rpcUrl: 'R' }) } }))

const at = (path: string) => render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)

describe('App routes', () => {
  beforeEach(() => {
    vi.mocked(slaService.getSLAs).mockResolvedValue([])
    vi.mocked(slaService.getSLA).mockResolvedValue(undefined)
    vi.mocked(slaService.getObservations).mockResolvedValue([])
    vi.mocked(slaService.getConsensus).mockResolvedValue(undefined)
    vi.mocked(slaService.getMonitors).mockResolvedValue([])
    vi.mocked(uptimeService.getState).mockResolvedValue('UP')
  })
  it('renders the shell and dashboard at /', async () => {
    at('/')
    expect(screen.getByRole('link', { name: 'SLAna home' })).toBeInTheDocument()
    expect(await screen.findByText('No agreements yet')).toBeInTheDocument()
  })
  it('renders create page at /create', () => {
    at('/create')
    expect(screen.getByRole('heading', { name: 'Create an SLA' })).toBeInTheDocument()
  })
  it('renders monitoring page at /monitoring', async () => {
    at('/monitoring')
    expect(await screen.findByText('Uptime service UP')).toBeInTheDocument()
  })
  it('renders the uptime deal page at /deal', async () => {
    at('/deal')
    expect(screen.getByRole('heading', { name: 'Uptime deal' })).toBeInTheDocument()
    expect(await screen.findByText('Connect a wallet to create a deal.')).toBeInTheDocument()
  })
  it('renders SLA details route (not found for unknown id)', async () => {
    at('/sla/unknown')
    expect(await screen.findByText('Agreement not found')).toBeInTheDocument()
  })
  it('renders the not found page for unknown paths', () => {
    at('/nope/nothing')
    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
