import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { slaService } from '../services/solana/slaService'
import { WALLET } from '../test/utils'
import { CreateSLAPage } from './CreateSLAPage'

const wallet = vi.hoisted(() => ({ publicKey: null as unknown, setVisible: vi.fn() }))
vi.mock('@solana/wallet-adapter-react', () => ({ useWallet: () => ({ publicKey: wallet.publicKey }) }))
vi.mock('@solana/wallet-adapter-react-ui', () => ({ useWalletModal: () => ({ setVisible: wallet.setVisible }) }))
vi.mock('../services/solana/slaService', () => ({ slaService: { createSLA: vi.fn() } }))
const createSLA = vi.mocked(slaService.createSLA)

const VALID_PROVIDER = 'So11111111111111111111111111111111111111112'

function setup() {
  return render(
    <MemoryRouter initialEntries={['/create']}>
      <Routes>
        <Route path="/create" element={<CreateSLAPage />} />
        <Route path="/sla/:id" element={<p>created {location.pathname}</p>} />
      </Routes>
    </MemoryRouter>,
  )
}
const submit = () => userEvent.click(screen.getByRole('button', { name: /create demo sla/i }))
async function fillValid() {
  await userEvent.type(screen.getByLabelText('Agreement name'), '  My API  ')
  await userEvent.type(screen.getByLabelText('API endpoint'), 'https://api.example.com/health')
  await userEvent.click(screen.getByLabelText('Provider wallet address'))
  await userEvent.paste(VALID_PROVIDER)
}

describe('CreateSLAPage', () => {
  beforeEach(() => {
    createSLA.mockReset()
    wallet.publicKey = null
    wallet.setVisible.mockReset()
  })

  it('renders the form with defaults', () => {
    setup()
    expect(screen.getByRole('heading', { name: 'Create an SLA' })).toBeInTheDocument()
    expect(screen.getByLabelText('Escrow amount')).toHaveValue(10)
    expect(screen.getByLabelText('Required uptime')).toHaveValue(99.9)
    expect(screen.getByLabelText('SLA duration')).toHaveValue('7')
    expect(screen.getByLabelText('Request timeout')).toHaveValue(2000)
    expect(screen.getByText(/Demo mode/)).toBeInTheDocument()
  })

  it('validates required fields and does not submit', async () => {
    wallet.publicKey = { toBase58: () => WALLET }
    setup()
    await submit()
    expect(screen.getByText('Give this agreement a name.')).toBeInTheDocument()
    expect(screen.getByText('Enter a valid HTTPS URL.')).toBeInTheDocument()
    expect(screen.getByText('Enter a valid Solana wallet address.')).toBeInTheDocument()
    expect(screen.getByLabelText('Agreement name')).toHaveAttribute('aria-invalid', 'true')
    expect(createSLA).not.toHaveBeenCalled()
    expect(wallet.setVisible).not.toHaveBeenCalled()
  })

  it('rejects a non-HTTPS endpoint and clears a field error when edited', async () => {
    setup()
    await userEvent.type(screen.getByLabelText('API endpoint'), 'http://insecure.example.com')
    await submit()
    expect(screen.getByText('Use an HTTPS endpoint.')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('API endpoint'), 'x')
    expect(screen.queryByText('Use an HTTPS endpoint.')).not.toBeInTheDocument()
  })

  it('validates numeric ranges', async () => {
    setup()
    await fillValid()
    await userEvent.clear(screen.getByLabelText('Escrow amount'))
    await userEvent.type(screen.getByLabelText('Escrow amount'), '20000')
    await userEvent.clear(screen.getByLabelText('Required uptime'))
    await userEvent.type(screen.getByLabelText('Required uptime'), '101')
    await userEvent.clear(screen.getByLabelText('Request timeout'))
    await userEvent.type(screen.getByLabelText('Request timeout'), '50')
    await submit()
    expect(screen.getByText('Enter an amount between 0 and 10,000 SOL.')).toBeInTheDocument()
    expect(screen.getByText('Enter a percentage above 0 and up to 100.')).toBeInTheDocument()
    expect(screen.getByText('Enter a timeout between 100 and 30,000 ms.')).toBeInTheDocument()
    expect(createSLA).not.toHaveBeenCalled()
  })

  it('opens the wallet modal for a valid form without a connected wallet', async () => {
    setup()
    await fillValid()
    await submit()
    expect(wallet.setVisible).toHaveBeenCalledWith(true)
    expect(createSLA).not.toHaveBeenCalled()
  })

  it('creates the SLA with trimmed values and navigates to its page', async () => {
    wallet.publicKey = { toBase58: () => WALLET }
    createSLA.mockResolvedValue({ id: 'sla-123' } as Awaited<ReturnType<typeof slaService.createSLA>>)
    setup()
    await fillValid()
    await submit()
    expect(createSLA).toHaveBeenCalledWith(expect.objectContaining({
      name: 'My API', endpoint: 'https://api.example.com/health', providerWallet: VALID_PROVIDER,
      customerWallet: WALLET, monitorCount: 5, escrowSol: 10, requiredUptime: 99.9, durationDays: 7, timeoutMs: 2000,
    }))
    expect(await screen.findByText(/created/)).toBeInTheDocument()
  })

  it('shows a submit error and re-enables the button', async () => {
    wallet.publicKey = { toBase58: () => WALLET }
    createSLA.mockRejectedValue(new Error('Storage full'))
    setup()
    await fillValid()
    await submit()
    expect(await screen.findByText('Storage full')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create demo sla/i })).toBeEnabled()
  })

  it('uses a generic submit error for non-Error rejections', async () => {
    wallet.publicKey = { toBase58: () => WALLET }
    createSLA.mockRejectedValue('x')
    setup()
    await fillValid()
    await submit()
    expect(await screen.findByText('Could not create the agreement.')).toBeInTheDocument()
  })

  it('disables the button while submitting', async () => {
    wallet.publicKey = { toBase58: () => WALLET }
    createSLA.mockReturnValue(new Promise(() => {}))
    setup()
    await fillValid()
    await submit()
    expect(await screen.findByRole('button', { name: /creating agreement/i })).toBeDisabled()
  })
})
