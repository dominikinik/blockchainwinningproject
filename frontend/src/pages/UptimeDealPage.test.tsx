import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PublicKey } from '@solana/web3.js'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useWallet } from '@solana/wallet-adapter-react'
import { dealApi, type TrackedDeal } from '../services/deal/dealApi'
import { acceptDeal, cancelDeal, openDeal, requestAirdrop } from '../services/deal/dealService'
import { uptimeService } from '../services/uptime/uptimeService'
import { renderAt, PROVIDER, WALLET } from '../test/utils'
import { dealLink, dealVerdict, formatLamports, reclaimableAt, UptimeDealPage } from './UptimeDealPage'

const ORACLE = 'DGT7vw5vTR1aaUvNkK7rAMJH8oQjUoETzbW7bp56GjDd'
const DEAL = 'F3syGDiKt7sX9srgBWjZtB3ZLyJ3kSAfyaNsm2hnTuq3'
const OUTSIDER = 'DGT7vw5vTR1aaUvNkK7rAMJH8oQjUoETzbW7bp56GjDd'
const getBalance = vi.fn()
const connection = { getBalance }
const sendTransaction = vi.fn()
const writeText = vi.fn()

vi.mock('@solana/wallet-adapter-react', () => ({ useWallet: vi.fn(), useConnection: () => ({ connection }) }))
vi.mock('@solana/wallet-adapter-react-ui', () => ({ WalletMultiButton: () => <button>Wallet</button> }))
vi.mock('../services/deal/dealApi', () => ({ dealApi: { getConfig: vi.fn(), get: vi.fn(), list: vi.fn(), setServiceUp: vi.fn() } }))
vi.mock('../services/deal/dealService', () => ({ openDeal: vi.fn(), acceptDeal: vi.fn(), requestAirdrop: vi.fn(), cancelDeal: vi.fn() }))
const cluster = vi.hoisted(() => ({ url: 'http://localhost:8899' }))
vi.mock('../config/solana', async (importActual) => ({
  ...(await importActual<typeof import('../config/solana')>()),
  get SOLANA_RPC_URL() { return cluster.url },
}))
vi.mock('../services/uptime/uptimeService', () => ({ uptimeService: { getState: vi.fn() } }))

const config = { programId: 'EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r', oracle: ORACLE, rpcUrl: 'http://127.0.0.1:8899' }

function trackedDeal(overrides: Partial<TrackedDeal> = {}): TrackedDeal {
  const now = Date.now()
  return {
    address: DEAL, payer: WALLET, recipient: PROVIDER, amountLamports: 500_000_000, guaranteeLamports: 700_000_000, durationSeconds: 10,
    acceptDeadline: new Date(now + 86_400_000).toISOString(),
    startsAt: new Date(now - 1000).toISOString(), endsAt: new Date(now + 9000).toISOString(), status: 'ACTIVE',
    upSeconds: null, totalSeconds: null, paidToRecipient: null, signature: null, attempts: 0, error: null, ...overrides,
  }
}

function proposal(overrides: Partial<TrackedDeal> = {}): TrackedDeal {
  return trackedDeal({ status: 'PROPOSED', startsAt: null, endsAt: null, ...overrides })
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
    vi.mocked(dealApi.list).mockReset().mockResolvedValue([])
    vi.mocked(dealApi.setServiceUp).mockReset()
    vi.mocked(openDeal).mockReset()
    vi.mocked(acceptDeal).mockReset().mockResolvedValue()
    vi.mocked(cancelDeal).mockReset().mockResolvedValue()
    cluster.url = 'http://localhost:8899'
    vi.mocked(requestAirdrop).mockReset().mockResolvedValue()
    vi.mocked(uptimeService.getState).mockReset().mockResolvedValue('UP')
    writeText.mockReset().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
  })

  it('asks for a wallet before a deal can be proposed', async () => {
    connect(null)
    renderAt(<UptimeDealPage />)
    expect(screen.getByText('Connect a wallet to propose or accept a deal.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Wallet' })).toBeInTheDocument()
    expect(await screen.findByText('DGT7vw...56GjDd')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Propose deal/ })).toBeDisabled()
  })

  it('shows the wallet, its balance, the service state and the oracle', async () => {
    renderAt(<UptimeDealPage />)
    expect(await screen.findByTestId('wallet-balance')).toHaveTextContent('2 SOL')
    expect(screen.getByTestId('wallet-address')).toHaveAttribute('title', WALLET)
    expect(await screen.findByTestId('service-state')).toHaveTextContent('UP')
    expect(screen.getByText('DGT7vw...56GjDd')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: /Propose deal/ })).toBeEnabled())
  })

  it('copies the wallet address so a provider can share it with the payer', async () => {
    renderAt(<UptimeDealPage />)
    await userEvent.click(screen.getByRole('button', { name: /Copy my address/ }))
    expect(writeText).toHaveBeenCalledWith(WALLET)
  })

  it('requests an airdrop for the connected wallet', async () => {
    renderAt(<UptimeDealPage />)
    await userEvent.click(screen.getByRole('button', { name: /Airdrop 2 SOL/ }))
    expect(requestAirdrop).toHaveBeenCalledWith(connection, new PublicKey(WALLET), 2_000_000_000)
  })

  it('proposes a deal and follows it through acceptance until the provider is paid', async () => {
    let onChain = proposal()
    vi.mocked(openDeal).mockResolvedValue(onChain)
    vi.mocked(dealApi.get).mockImplementation(async () => onChain)
    getBalance.mockImplementation(async (key: PublicKey) => key.toBase58() === WALLET ? 1_499_995_000 : 1_200_000_000)
    renderAt(<UptimeDealPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: /Propose deal/ })).toBeEnabled())

    await userEvent.type(screen.getByLabelText('Provider address'), `  ${PROVIDER} `)
    const guarantee = screen.getByLabelText('Provider guarantee (SOL)')
    await userEvent.clear(guarantee)
    await userEvent.type(guarantee, '0.7')
    await userEvent.click(screen.getByRole('button', { name: /Propose deal/ }))

    expect(openDeal).toHaveBeenCalledWith(expect.objectContaining({
      connection, payer: new PublicKey(WALLET), sendTransaction, config, recipient: PROVIDER,
      amountLamports: 500_000_000n, guaranteeLamports: 700_000_000n, durationSeconds: 10,
    }))
    expect(await screen.findByTestId('deal-verdict')).toHaveTextContent('Waiting for the provider to accept')
    expect(screen.getByRole('button', { name: /Copy link/ })).toBeInTheDocument()
    expect(screen.getByTestId('deal-escrow')).toHaveTextContent('0.5 SOL')

    onChain = trackedDeal()
    expect(await screen.findByText(/Measuring uptime · \ds left/, {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByTestId('deal-escrow')).toHaveTextContent('1.2 SOL')

    onChain = trackedDeal({ status: 'SETTLED', upSeconds: 10, totalSeconds: 10, paidToRecipient: true, signature: '5vXy1234567890abcdefSIG' })
    expect(await screen.findByText('Paid to recipient', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByTestId('deal-measured')).toHaveTextContent('10/10 s up (100.0%)')
    await waitFor(() => expect(screen.getByTestId('recipient-balance')).toHaveTextContent('1.2 SOL'))
    expect(screen.getByText('5vXy12...defSIG')).toBeInTheDocument()
  })

  it('validates the window, the payment and the guarantee before sending', async () => {
    renderAt(<UptimeDealPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: /Propose deal/ })).toBeEnabled())
    const duration = screen.getByLabelText('Window (seconds)')
    await userEvent.clear(duration)
    await userEvent.type(duration, '0')
    await userEvent.click(screen.getByRole('button', { name: /Propose deal/ }))
    expect(screen.getByText('Enter a window of 1 to 3600 seconds.')).toBeInTheDocument()

    await userEvent.clear(screen.getByLabelText('Provider guarantee (SOL)'))
    await userEvent.click(screen.getByRole('button', { name: /Propose deal/ }))
    expect(screen.getByText('Enter a provider guarantee in SOL.')).toBeInTheDocument()

    await userEvent.clear(screen.getByLabelText('Payment (SOL)'))
    await userEvent.click(screen.getByRole('button', { name: /Propose deal/ }))
    expect(screen.getByText('Enter a payment in SOL.')).toBeInTheDocument()
    expect(openDeal).not.toHaveBeenCalled()
  })

  it('shows why a deal could not be proposed', async () => {
    vi.mocked(openDeal).mockRejectedValue(new Error('Deal names oracle X'))
    renderAt(<UptimeDealPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: /Propose deal/ })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: /Propose deal/ }))
    expect(await screen.findByText('Deal names oracle X')).toBeInTheDocument()
    expect(screen.queryByTestId('deal-verdict')).not.toBeInTheDocument()
  })

  it('blocks proposals when the app and the service use different clusters', async () => {
    cluster.url = 'https://api.devnet.solana.com'
    renderAt(<UptimeDealPage />)
    const alert = await screen.findByText(/settles on http:\/\/127\.0\.0\.1:8899, but this app is connected to https:\/\/api\.devnet\.solana\.com/)
    expect(alert).toHaveTextContent('VITE_SOLANA_RPC_URL=http://127.0.0.1:8899')
    expect(screen.getByRole('button', { name: /Propose deal/ })).toBeDisabled()
  })

  it('does not warn when localhost and 127.0.0.1 are the same cluster', async () => {
    renderAt(<UptimeDealPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: /Propose deal/ })).toBeEnabled())
    expect(screen.queryByText(/VITE_SOLANA_RPC_URL/)).not.toBeInTheDocument()
  })

  /** Opens the page from a deal link, as the provider does with the link the payer sent. */
  async function openLinkedDeal(deal: TrackedDeal, wallet: string) {
    connect(new PublicKey(wallet))
    vi.mocked(dealApi.get).mockResolvedValue(deal)
    renderAt(<UptimeDealPage />, `/deal?deal=${deal.address}`)
    await screen.findByTestId('deal-verdict')
    await waitFor(() => expect(dealApi.getConfig).toHaveBeenCalled())
  }

  it('lists the proposals addressed to the connected wallet and opens one without reloading', async () => {
    const offer = proposal()
    const other = '4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T'
    vi.mocked(dealApi.list).mockResolvedValue([
      offer,
      proposal({ address: other, recipient: OUTSIDER }),
      trackedDeal({ address: other }),
      proposal({ address: other, acceptDeadline: new Date(Date.now() - 1000).toISOString() }),
    ])
    vi.mocked(dealApi.get).mockResolvedValue(offer)
    connect(new PublicKey(PROVIDER))
    renderAt(<UptimeDealPage />)

    const inbox = await screen.findByRole('region', { name: 'Proposals for you' })
    expect(inbox).toHaveTextContent('8xF2mT...jK31Qz pays 0.5 SOL · you lock 0.7 SOL · 10s')
    expect(screen.getAllByRole('button', { name: 'Review' })).toHaveLength(1)
    await userEvent.click(screen.getByRole('button', { name: 'Review' }))

    expect(dealApi.get).toHaveBeenCalledWith(DEAL)
    expect(await screen.findByRole('button', { name: 'Accept and lock 0.7 SOL' })).toBeEnabled()
    expect(screen.queryByRole('region', { name: 'Proposals for you' })).not.toBeInTheDocument()
  })

  it('shows no proposals to a wallet they are not addressed to', async () => {
    vi.mocked(dealApi.list).mockResolvedValue([proposal()])
    renderAt(<UptimeDealPage />)
    await waitFor(() => expect(dealApi.list).toHaveBeenCalled())
    expect(screen.queryByRole('region', { name: 'Proposals for you' })).not.toBeInTheDocument()
  })

  it('lets the provider review and accept a proposal from its link', async () => {
    const offer = proposal()
    await openLinkedDeal(offer, PROVIDER)
    expect(dealApi.get).toHaveBeenCalledWith(DEAL)
    expect(screen.getByTestId('deal-payment')).toHaveTextContent('0.5 SOL')
    expect(screen.getByTestId('deal-guarantee')).toHaveTextContent('0.7 SOL')
    expect(screen.queryByRole('button', { name: 'Withdraw proposal' })).not.toBeInTheDocument()

    vi.mocked(dealApi.get).mockResolvedValue(trackedDeal())
    await userEvent.click(await screen.findByRole('button', { name: 'Accept and lock 0.7 SOL' }))

    expect(acceptDeal).toHaveBeenCalledWith({ connection, recipient: new PublicKey(PROVIDER), sendTransaction, config, deal: offer })
    expect(await screen.findByText(/Measuring uptime/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Accept and lock/ })).not.toBeInTheDocument()
  })

  it('lets the provider reject a proposal', async () => {
    const offer = proposal()
    await openLinkedDeal(offer, PROVIDER)
    await userEvent.click(await screen.findByRole('button', { name: 'Reject' }))
    expect(cancelDeal).toHaveBeenCalledWith({
      connection, signer: new PublicKey(PROVIDER), sendTransaction, programId: new PublicKey(config.programId), deal: offer,
    })
    expect(await screen.findByText('Cancelled · deposits returned')).toBeInTheDocument()
  })

  it('shows why the acceptance failed', async () => {
    vi.mocked(acceptDeal).mockRejectedValue(new Error('Transaction failed: TermsMismatch'))
    await openLinkedDeal(proposal(), PROVIDER)
    await userEvent.click(await screen.findByRole('button', { name: /Accept and lock/ }))
    expect(await screen.findByText('Transaction failed: TermsMismatch')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Accept and lock/ })).toBeEnabled()
  })

  it('disables acceptance once the proposal has expired', async () => {
    await openLinkedDeal(proposal({ acceptDeadline: new Date(Date.now() - 1000).toISOString() }), PROVIDER)
    expect(screen.getByTestId('deal-verdict')).toHaveTextContent('Proposal expired')
    expect(await screen.findByRole('button', { name: /Accept and lock/ })).toBeDisabled()
  })

  it('lets the payer withdraw its proposal and share the link', async () => {
    const offer = proposal()
    await openLinkedDeal(offer, WALLET)
    expect(screen.queryByRole('button', { name: /Accept and lock/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Copy link/ }))
    expect(writeText).toHaveBeenCalledWith(dealLink(DEAL))

    await userEvent.click(await screen.findByRole('button', { name: 'Withdraw proposal' }))
    expect(cancelDeal).toHaveBeenCalledWith({
      connection, signer: new PublicKey(WALLET), sendTransaction, programId: new PublicKey(config.programId), deal: offer,
    })
    expect(await screen.findByText('Cancelled · deposits returned')).toBeInTheDocument()
  })

  it('offers no actions on a proposal to a wallet outside the deal', async () => {
    await openLinkedDeal(proposal(), OUTSIDER)
    expect(screen.getByTestId('deal-verdict')).toHaveTextContent('Waiting for the provider to accept')
    for (const name of [/Accept and lock/, 'Reject', 'Withdraw proposal', /Copy link/]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
  })

  it('reports a deal link the service does not know', async () => {
    vi.mocked(dealApi.get).mockRejectedValue(new Error('Deal X is not registered'))
    renderAt(<UptimeDealPage />, `/deal?deal=${DEAL}`)
    expect(await screen.findByText(`Deal ${DEAL} could not be loaded: Deal X is not registered`)).toBeInTheDocument()
    expect(screen.queryByTestId('deal-verdict')).not.toBeInTheDocument()
  })

  it('offers a disabled reclaim button for a failed deal until the timeout has passed', async () => {
    const now = Date.now()
    await openLinkedDeal(trackedDeal({ status: 'FAILED', error: 'rpc down', startsAt: new Date(now - 20_000).toISOString(), endsAt: new Date(now - 10_000).toISOString() }), WALLET)
    expect(screen.getByRole('button', { name: 'Reclaim deposits' })).toBeDisabled()
    expect(screen.getByText(/Reclaim available/)).toBeInTheDocument()
  })

  it.each([
    ['the payer', WALLET],
    ['the provider', PROVIDER],
  ])('lets %s reclaim the deposits of a failed deal once the timeout has passed', async (_name, wallet) => {
    const now = Date.now()
    const failed = trackedDeal({ status: 'FAILED', error: 'rpc down', startsAt: new Date(now - 700_000).toISOString(), endsAt: new Date(now - 690_000).toISOString() })
    await openLinkedDeal(failed, wallet)
    const button = screen.getByRole('button', { name: 'Reclaim deposits' })
    expect(button).toBeEnabled()
    await userEvent.click(button)
    expect(cancelDeal).toHaveBeenCalledWith({
      connection, signer: new PublicKey(wallet), sendTransaction, programId: new PublicKey(config.programId), deal: failed,
    })
    expect(await screen.findByText('Cancelled · deposits returned')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reclaim deposits' })).not.toBeInTheDocument()
  })

  it('offers reclaim for an active deal that is long past its window', async () => {
    const now = Date.now()
    await openLinkedDeal(trackedDeal({ startsAt: new Date(now - 700_000).toISOString(), endsAt: new Date(now - 690_000).toISOString() }), WALLET)
    expect(screen.getByRole('button', { name: 'Reclaim deposits' })).toBeEnabled()
  })

  it('does not offer reclaim for a running deal', async () => {
    await openLinkedDeal(trackedDeal(), WALLET)
    expect(screen.queryByRole('button', { name: 'Reclaim deposits' })).not.toBeInTheDocument()
  })

  it('does not offer reclaim to a wallet outside the deal', async () => {
    const now = Date.now()
    await openLinkedDeal(trackedDeal({ status: 'FAILED', startsAt: new Date(now - 700_000).toISOString(), endsAt: new Date(now - 690_000).toISOString() }), OUTSIDER)
    expect(screen.queryByRole('button', { name: 'Reclaim deposits' })).not.toBeInTheDocument()
  })

  it('shows why the reclaim failed', async () => {
    vi.mocked(cancelDeal).mockRejectedValue(new Error('Transaction failed: too early'))
    const now = Date.now()
    await openLinkedDeal(trackedDeal({ status: 'FAILED', startsAt: new Date(now - 700_000).toISOString(), endsAt: new Date(now - 690_000).toISOString() }), WALLET)
    await userEvent.click(screen.getByRole('button', { name: 'Reclaim deposits' }))
    expect(await screen.findByText('Transaction failed: too early')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reclaim deposits' })).toBeEnabled()
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
    [proposal({ acceptDeadline: '2026-10-04T12:00:00Z' }), 'Waiting for the provider to accept'],
    [proposal({ acceptDeadline: '2026-10-03T12:00:05Z' }), 'Proposal expired · withdraw it to get your payment back'],
    [at({}), 'Measuring uptime · 6s left'],
    [at({ startsAt: '2026-10-03T12:00:06Z' }), 'Window starts in a moment'],
    [at({ endsAt: '2026-10-03T12:00:05Z' }), 'Settling on chain…'],
    [at({ status: 'SETTLED', paidToRecipient: true }), 'Paid to recipient'],
    [at({ status: 'SETTLED', paidToRecipient: false }), 'Refunded to payer'],
    [at({ status: 'SETTLED', paidToRecipient: null }), 'Settled'],
    [at({ status: 'CANCELLED' }), 'Cancelled · deposits returned'],
    [at({ status: 'FAILED', error: 'insufficient funds' }), 'Settlement failed: insufficient funds'],
    [at({ status: 'FAILED' }), 'Settlement failed: unknown error'],
  ])('describes %#', (deal, text) => {
    expect(dealVerdict(deal, now)).toBe(text)
  })
})

describe('reclaimableAt', () => {
  it('opens 10 minutes after the window ends, and at once for a deal with no window', () => {
    expect(reclaimableAt(trackedDeal({ endsAt: '2026-10-03T12:00:00Z' }))).toBe(Date.parse('2026-10-03T12:10:00Z'))
    expect(reclaimableAt(proposal())).toBe(0)
  })
})

describe('dealLink', () => {
  it('points this page at the deal', () => {
    expect(dealLink(DEAL)).toBe(`${window.location.origin}/deal?deal=${DEAL}`)
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
