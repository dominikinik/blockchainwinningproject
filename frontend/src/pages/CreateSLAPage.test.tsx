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
    expect(screen.getByLabelText('Customer service payment')).toHaveValue(10)
    expect(screen.getByLabelText('Provider SLA guarantee')).toHaveValue(2)
    expect(screen.getByLabelText('Required uptime')).toHaveValue(99.9)
    expect(selected('SLA duration')).toBe('1 minute')
    expect(screen.queryByLabelText('Check interval')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Request timeout')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Required monitor consensus')).not.toBeInTheDocument()
  })

  it('offers every duration option', () => {
    setup()
    expect(Array.from((screen.getByLabelText('SLA duration') as HTMLSelectElement).options).map((o) => o.textContent))
      .toEqual(['30 seconds', '1 minute', '5 minutes', '15 minutes', '1 hour', '1 day', '7 days', '14 days', '30 days'])
  })

  it('previews the escrow and duration in the summary', async () => {
    setup()
    expect(screen.getByText('12 SOL')).toBeInTheDocument()
    expect(screen.getByText('1 minute', { selector: 'strong' })).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('Customer service payment'))
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
    await userEvent.type(screen.getByLabelText('Customer service payment'), '2.5')
    expect(screen.getByText('4.5 SOL')).toBeInTheDocument()
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
    await userEvent.clear(screen.getByLabelText('Customer service payment'))
    await userEvent.type(screen.getByLabelText('Customer service payment'), '20000')
    await userEvent.clear(screen.getByLabelText('Required uptime'))
    await userEvent.type(screen.getByLabelText('Required uptime'), '101')
    await submit()
    expect(screen.getByText('Customer payment must be between 0.000000001 and 10,000 SOL, with up to 9 decimals.')).toBeInTheDocument()
    expect(screen.getByText('Enter a percentage above 0 and up to 100.')).toBeInTheDocument()
    expect(createSLA).not.toHaveBeenCalled()
  })

  it.each(['0', '10001', '0.0000000001', ''])('rejects customer payment %j', async (amount) => {
    wallet.publicKey = { toBase58: () => WALLET }
    setup()
    await fillValid()
    await userEvent.clear(screen.getByLabelText('Customer service payment'))
    if (amount) await userEvent.type(screen.getByLabelText('Customer service payment'), amount)
    await submit()
    expect(screen.getByText('Customer payment must be between 0.000000001 and 10,000 SOL, with up to 9 decimals.')).toBeInTheDocument()
    expect(createSLA).not.toHaveBeenCalled()
  })

  it.each(['0.000000001', '10000', '.5'])('accepts customer payment %j', async (amount) => {
    wallet.publicKey = { toBase58: () => WALLET }
    createSLA.mockResolvedValue({ id: 'sla-1' } as Awaited<ReturnType<typeof slaService.createSLA>>)
    setup()
    await fillValid()
    await userEvent.clear(screen.getByLabelText('Customer service payment'))
    await userEvent.type(screen.getByLabelText('Customer service payment'), amount)
    await submit()
    expect(createSLA).toHaveBeenCalledWith(expect.objectContaining({ customerPaymentSol: Number(amount), providerGuaranteeSol: 2 }))
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
      customerWallet: WALLET, monitorCount: 1, consensusRequired: 1, customerPaymentSol: 10, providerGuaranteeSol: 2, requiredUptime: 99.9,
      durationDays: 60 / 86_400,
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
