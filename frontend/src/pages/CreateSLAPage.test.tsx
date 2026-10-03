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
const submitButton = () => screen.getByRole('button', { name: /^create sla$/i })
const submit = () => userEvent.click(submitButton())
const selected = (label: string) => (screen.getByLabelText(label) as HTMLSelectElement).selectedOptions[0].textContent
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
    expect(selected('SLA duration')).toBe('1 minute')
    expect(selected('Check interval')).toBe('Every 10 seconds')
    expect(screen.queryByLabelText('Request timeout')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Required monitor consensus')).not.toBeInTheDocument()
    expect(screen.getByText('Outages between scheduled checks may be missed.')).toBeInTheDocument()
  })

  it('offers every duration and check interval option', () => {
    setup()
    expect(Array.from((screen.getByLabelText('SLA duration') as HTMLSelectElement).options).map((o) => o.textContent))
      .toEqual(['30 seconds', '1 minute', '5 minutes', '15 minutes', '1 hour', '1 day', '7 days', '14 days', '30 days'])
    expect(Array.from((screen.getByLabelText('Check interval') as HTMLSelectElement).options).map((o) => o.textContent))
      .toEqual(['Every 10 seconds', 'Every 1 minute', 'Every 5 minutes', 'Every 10 minutes'])
  })

  it('previews the escrow and duration in the summary', async () => {
    setup()
    expect(screen.getByText('10 SOL')).toBeInTheDocument()
    expect(screen.getByText('1 minute', { selector: 'strong' })).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('Escrow amount'))
    expect(screen.getByText('—')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Escrow amount'), '2.5')
    expect(screen.getByText('2.5 SOL')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('SLA duration'), '7 days')
    expect(screen.getByText('7 days', { selector: 'strong' })).toBeInTheDocument()
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
    await submit()
    expect(screen.getByText('Enter 0.001 to 10,000 SOL, with up to 3 decimals.')).toBeInTheDocument()
    expect(screen.getByText('Enter a percentage above 0 and up to 100.')).toBeInTheDocument()
    expect(createSLA).not.toHaveBeenCalled()
  })

  it.each(['0', '0.0009', '1.2345', ''])('rejects escrow amount %j', async (amount) => {
    wallet.publicKey = { toBase58: () => WALLET }
    setup()
    await fillValid()
    await userEvent.clear(screen.getByLabelText('Escrow amount'))
    if (amount) await userEvent.type(screen.getByLabelText('Escrow amount'), amount)
    await submit()
    expect(screen.getByText('Enter 0.001 to 10,000 SOL, with up to 3 decimals.')).toBeInTheDocument()
    expect(createSLA).not.toHaveBeenCalled()
  })

  it.each(['0.001', '10000', '.5'])('accepts escrow amount %j', async (amount) => {
    wallet.publicKey = { toBase58: () => WALLET }
    createSLA.mockResolvedValue({ id: 'sla-1' } as Awaited<ReturnType<typeof slaService.createSLA>>)
    setup()
    await fillValid()
    await userEvent.clear(screen.getByLabelText('Escrow amount'))
    await userEvent.type(screen.getByLabelText('Escrow amount'), amount)
    await submit()
    expect(createSLA).toHaveBeenCalledWith(expect.objectContaining({ escrowSol: Number(amount) }))
  })

  it('warns about and rejects a check interval longer than the agreement', async () => {
    wallet.publicKey = { toBase58: () => WALLET }
    setup()
    await fillValid()
    await userEvent.selectOptions(screen.getByLabelText('SLA duration'), '30 seconds')
    await userEvent.selectOptions(screen.getByLabelText('Check interval'), 'Every 1 minute')
    expect(screen.getByText('Choose an interval no longer than the agreement.')).toBeInTheDocument()
    expect(screen.getByLabelText('Check interval')).toHaveAttribute('aria-invalid', 'true')
    await submit()
    expect(createSLA).not.toHaveBeenCalled()
    await userEvent.selectOptions(screen.getByLabelText('SLA duration'), '5 minutes')
    expect(screen.queryByText('Choose an interval no longer than the agreement.')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Check interval')).toHaveAttribute('aria-invalid', 'false')
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
      customerWallet: WALLET, monitorCount: 1, consensusRequired: 1, escrowSol: 10, requiredUptime: 99.9,
      durationDays: 60 / 86_400, checkIntervalMinutes: 10 / 60, timeoutMs: 2000,
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
    expect(submitButton()).toBeEnabled()
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
