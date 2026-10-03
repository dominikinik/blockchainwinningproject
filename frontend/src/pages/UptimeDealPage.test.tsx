import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PublicKey } from '@solana/web3.js'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useWallet } from '@solana/wallet-adapter-react'
import { dealApi, type TrackedDeal } from '../services/deal/dealApi'
import { openDeal, requestAirdrop } from '../services/deal/dealService'
import { uptimeService } from '../services/uptime/uptimeService'
import { renderAt, PROVIDER, WALLET } from '../test/utils'
import { dealVerdict, formatLamports, UptimeDealPage } from './UptimeDealPage'

const ORACLE = 'DGT7vw5vTR1aaUvNkK7rAMJH8oQjUoETzbW7bp56GjDd'
const DEAL = 'F3syGDiKt7sX9srgBWjZtB3ZLyJ3kSAfyaNsm2hnTuq3'
const getBalance = vi.fn()
const connection = { getBalance }
const sendTransaction = vi.fn()

vi.mock('@solana/wallet-adapter-react', () => ({ useWallet: vi.fn(), useConnection: () => ({ connection }) }))
vi.mock('@solana/wallet-adapter-react-ui', () => ({ WalletMultiButton: () => <button>Wallet</button> }))
vi.mock('../services/deal/dealApi', () => ({ dealApi: { getConfig: vi.fn(), get: vi.fn(), setServiceUp: vi.fn() } }))
vi.mock('../services/deal/dealService', () => ({ openDeal: vi.fn(), requestAirdrop: vi.fn() }))
vi.mock('../services/uptime/uptimeService', () => ({ uptimeService: { getState: vi.fn() } }))

const config = { programId: 'EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r', oracle: ORACLE, rpcUrl: 'http://127.0.0.1:8899' }

function trackedDeal(overrides: Partial<TrackedDeal> = {}): TrackedDeal {
  const now = Date.now()
  return {
    address: DEAL, payer: WALLET, recipient: PROVIDER, amountLamports: 500_000_000, durationSeconds: 10,
    startsAt: new Date(now - 1000).toISOString(), endsAt: new Date(now + 9000).toISOString(), status: 'ACTIVE',
    upSeconds: null, totalSeconds: null, paidToRecipient: null, signature: null, attempts: 0, error: null, ...overrides,
  }
}

function connect(publicKey: PublicKey | null = new PublicKey(WALLET)) {
  vi.mocked(useWallet).mockReturnValue({ publicKey, sendTransaction } as unknown as ReturnType<typeof useWallet>)
}

describe('UptimeDealPage', () => {
  beforeEach(() => {
    connect()
    getBalance.mockReset().mockImplementation(async (key: PublicKey) => key.toBase58() === WALLET ? 2_000_000_000 : 0)
    vi.mocked(dealApi.getConfig).mockReset().mockResolvedValue(config)
    vi.mocked(dealApi.get).mockReset()
    vi.mocked(dealApi.setServiceUp).mockReset()
    vi.mocked(openDeal).mockReset()
    vi.mocked(requestAirdrop).mockReset().mockResolvedValue()
    vi.mocked(uptimeService.getState).mockReset().mockResolvedValue('UP')
  })

  it('asks for a wallet before a deal can be created', async () => {
    connect(null)
    renderAt(<UptimeDealPage />)
    expect(screen.getByText('Connect a wallet to create a deal.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Wallet' })).toBeInTheDocument()
    expect(await screen.findByText('DGT7vw...56GjDd')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Create deal/ })).toBeDisabled()
  })

  it('shows the wallet balance, the service state and the oracle', async () => {
    renderAt(<UptimeDealPage />)
    expect(await screen.findByTestId('wallet-balance')).toHaveTextContent('2 SOL')
    expect(await screen.findByTestId('service-state')).toHaveTextContent('UP')
    expect(screen.getByText('DGT7vw...56GjDd')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: /Create deal/ })).toBeEnabled())
  })

  it('requests an airdrop for the connected wallet', async () => {
    renderAt(<UptimeDealPage />)
    await userEvent.click(screen.getByRole('button', { name: /Airdrop 2 SOL/ }))
    expect(requestAirdrop).toHaveBeenCalledWith(connection, new PublicKey(WALLET), 2_000_000_000)
  })

  it('creates a deal and follows it until the recipient is paid', async () => {
    vi.mocked(openDeal).mockResolvedValue(trackedDeal())
    vi.mocked(dealApi.get).mockResolvedValueOnce(trackedDeal()).mockResolvedValue(trackedDeal({
      status: 'SETTLED', upSeconds: 10, totalSeconds: 10, paidToRecipient: true, signature: '5vXy1234567890abcdefSIG',
    }))
    getBalance.mockImplementation(async (key: PublicKey) => key.toBase58() === WALLET ? 1_499_995_000 : 500_000_000)
    renderAt(<UptimeDealPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: /Create deal/ })).toBeEnabled())

    await userEvent.type(screen.getByLabelText('Recipient address'), `  ${PROVIDER} `)
    await userEvent.click(screen.getByRole('button', { name: /Create deal/ }))

    expect(openDeal).toHaveBeenCalledWith(expect.objectContaining({
      connection, payer: new PublicKey(WALLET), sendTransaction, config, recipient: PROVIDER,
      amountLamports: 500_000_000n, durationSeconds: 10,
    }))
    expect(await screen.findByTestId('deal-verdict')).toHaveTextContent(/Measuring uptime · \ds left/)
    expect(await screen.findByText('Paid to recipient', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByTestId('deal-measured')).toHaveTextContent('10/10 s up (100.0%)')
    await waitFor(() => expect(screen.getByTestId('recipient-balance')).toHaveTextContent('0.5 SOL'))
    expect(screen.getByText('5vXy12...defSIG')).toBeInTheDocument()
  })

  it('validates the window and amount before sending', async () => {
    renderAt(<UptimeDealPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: /Create deal/ })).toBeEnabled())
    const duration = screen.getByLabelText('Window (seconds)')
    await userEvent.clear(duration)
    await userEvent.type(duration, '0')
    await userEvent.click(screen.getByRole('button', { name: /Create deal/ }))
    expect(screen.getByText('Enter a window of 1 to 3600 seconds.')).toBeInTheDocument()

    await userEvent.clear(screen.getByLabelText('Amount (SOL)'))
    await userEvent.click(screen.getByRole('button', { name: /Create deal/ }))
    expect(screen.getByText('Enter an amount in SOL.')).toBeInTheDocument()
    expect(openDeal).not.toHaveBeenCalled()
  })

  it('shows why a deal could not be created', async () => {
    vi.mocked(openDeal).mockRejectedValue(new Error('Deal names oracle X'))
    renderAt(<UptimeDealPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: /Create deal/ })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: /Create deal/ }))
    expect(await screen.findByText('Deal names oracle X')).toBeInTheDocument()
    expect(screen.queryByTestId('deal-verdict')).not.toBeInTheDocument()
  })

  it('switches the service off and on to simulate an outage', async () => {
    vi.mocked(dealApi.setServiceUp).mockResolvedValueOnce('DOWN').mockResolvedValueOnce('UP')
    renderAt(<UptimeDealPage />)
    await userEvent.click(await screen.findByRole('button', { name: 'Simulate outage' }))
    expect(dealApi.setServiceUp).toHaveBeenCalledWith(false)
    expect(screen.getByTestId('service-state')).toHaveTextContent('DOWN')
    await userEvent.click(screen.getByRole('button', { name: 'Restore service' }))
    expect(dealApi.setServiceUp).toHaveBeenLastCalledWith(true)
    expect(screen.getByTestId('service-state')).toHaveTextContent('UP')
  })

  it('reports an unreachable uptime service', async () => {
    vi.mocked(dealApi.getConfig).mockRejectedValue(new Error('Uptime service returned 502.'))
    vi.mocked(uptimeService.getState).mockRejectedValue(new Error('down'))
    renderAt(<UptimeDealPage />)
    expect(await screen.findByText('Uptime service unavailable: Uptime service returned 502.')).toBeInTheDocument()
    expect(screen.getByTestId('service-state')).toHaveTextContent('Unavailable')
    expect(screen.getByRole('button', { name: 'Simulate outage' })).toBeDisabled()
  })
})

describe('dealVerdict', () => {
  const now = Date.parse('2026-10-03T12:00:05Z')
  const at = (overrides: Partial<TrackedDeal>) => trackedDeal({ startsAt: '2026-10-03T12:00:01Z', endsAt: '2026-10-03T12:00:11Z', ...overrides })

  it.each([
    [at({}), 'Measuring uptime · 6s left'],
    [at({ startsAt: '2026-10-03T12:00:06Z' }), 'Window starts in a moment'],
    [at({ endsAt: '2026-10-03T12:00:05Z' }), 'Settling on chain…'],
    [at({ status: 'SETTLED', paidToRecipient: true }), 'Paid to recipient'],
    [at({ status: 'SETTLED', paidToRecipient: false }), 'Refunded to payer'],
    [at({ status: 'SETTLED', paidToRecipient: null }), 'Settled'],
    [at({ status: 'FAILED', error: 'insufficient funds' }), 'Settlement failed: insufficient funds'],
    [at({ status: 'FAILED' }), 'Settlement failed: unknown error'],
  ])('describes %#', (deal, text) => {
    expect(dealVerdict(deal, now)).toBe(text)
  })
})

describe('formatLamports', () => {
  it('formats lamports as SOL', () => {
    expect(formatLamports(null)).toBe('—')
    expect(formatLamports(0)).toBe('0 SOL')
    expect(formatLamports(500_000_000)).toBe('0.5 SOL')
    expect(formatLamports(1_499_995_000)).toBe('1.499995 SOL')
  })
})
