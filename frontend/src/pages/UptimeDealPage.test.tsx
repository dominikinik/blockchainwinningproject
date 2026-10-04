import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PublicKey } from '@solana/web3.js'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useWallet } from '@solana/wallet-adapter-react'
import { dealApi } from '../services/deal/dealApi'
import type { OnChainDeal } from '../services/deal/dealProgram'
import { acceptDeal, cancelDeal, openDeal, readDeal, readOutcome, requestAirdrop, settleDeal } from '../services/deal/dealService'
import { uptimeService } from '../services/uptime/uptimeService'
import { renderAt, PROVIDER, WALLET } from '../test/utils'
import { counterLine, dealVerdict, formatBps, formatLamports, projection, settleOpensAt, UptimeDealPage } from './UptimeDealPage'

const ORACLE = 'DGT7vw5vTR1aaUvNkK7rAMJH8oQjUoETzbW7bp56GjDd'
const DEAL = 'F3syGDiKt7sX9srgBWjZtB3ZLyJ3kSAfyaNsm2hnTuq3'
const getBalance = vi.fn()
const connection = { getBalance }
const sendTransaction = vi.fn()

vi.mock('@solana/wallet-adapter-react', () => ({ useWallet: vi.fn(), useConnection: () => ({ connection }) }))
vi.mock('@solana/wallet-adapter-react-ui', () => ({ WalletMultiButton: () => <button>Wallet</button> }))
vi.mock('../services/deal/dealApi', () => ({ dealApi: { getConfig: vi.fn(), setServiceUp: vi.fn() } }))
vi.mock('../services/deal/dealService', () => ({
  openDeal: vi.fn(), requestAirdrop: vi.fn(), cancelDeal: vi.fn(), acceptDeal: vi.fn(), settleDeal: vi.fn(), readDeal: vi.fn(), readOutcome: vi.fn(),
}))
const cluster = vi.hoisted(() => ({ url: 'http://localhost:8899' }))
vi.mock('../config/solana', async (importActual) => ({
  ...(await importActual<typeof import('../config/solana')>()),
  get SOLANA_RPC_URL() { return cluster.url },
}))
vi.mock('../services/uptime/uptimeService', () => ({ uptimeService: { getState: vi.fn() } }))

const config = { programId: 'EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r', oracle: ORACLE, rpcUrl: 'http://127.0.0.1:8899', checkIntervalSeconds: 2 }
const nowSeconds = () => Math.floor(Date.now() / 1000)

function chainDeal(overrides: Partial<OnChainDeal> = {}): OnChainDeal {
  return {
    payer: WALLET, recipient: PROVIDER, oracle: ORACLE, dealId: 1n, amountLamports: 500_000_000n, providerStakeLamports: 0n, acceptDeadline: nowSeconds() + 86_400, active: true,
    startsAt: nowSeconds() - 1, durationSeconds: 10, checkIntervalSeconds: 1, minUptimeBps: 9_900, totalRounds: 10, upChecks: 0, downChecks: 0,
    recorded: new Uint8Array(2), ...overrides,
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
    vi.mocked(dealApi.setServiceUp).mockReset()
    vi.mocked(openDeal).mockReset().mockResolvedValue(DEAL)
    vi.mocked(readDeal).mockReset().mockResolvedValue(chainDeal())
    vi.mocked(readOutcome).mockReset().mockResolvedValue(null)
    vi.mocked(cancelDeal).mockReset().mockResolvedValue()
    vi.mocked(acceptDeal).mockReset().mockResolvedValue()
    vi.mocked(settleDeal).mockReset().mockResolvedValue()
    cluster.url = 'http://localhost:8899'
    vi.mocked(requestAirdrop).mockReset().mockResolvedValue()
    vi.mocked(uptimeService.getState).mockReset().mockResolvedValue('UP')
  })

  async function ready() {
    renderAt(<UptimeDealPage />)
    await waitFor(() => expect(screen.getByRole('button', { name: /Create deal/ })).toBeEnabled())
  }

  async function createDeal() {
    await ready()
    await userEvent.type(screen.getByLabelText('Recipient address'), PROVIDER)
    await userEvent.click(screen.getByRole('button', { name: /Create deal/ }))
    await screen.findByTestId('deal-verdict')
  }

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

  it('creates a deal with every term and follows the on-chain counters until the program pays the recipient', async () => {
    vi.mocked(readDeal).mockResolvedValueOnce(chainDeal({ upChecks: 3, downChecks: 0 })).mockResolvedValue(null)
    vi.mocked(readOutcome).mockResolvedValue({
      signature: '5vXy1234567890abcdefSIG',
      outcome: { cancelled: false, paidToRecipient: true, upChecks: 10, downChecks: 0, totalRounds: 10, minUptimeBps: 9_900, payoutLamports: 500_000_000n },
    })
    getBalance.mockImplementation(async (key: PublicKey) => key.toBase58() === WALLET ? 1_499_995_000 : 500_000_000)
    await ready()

    await userEvent.type(screen.getByLabelText('Recipient address'), `  ${PROVIDER} `)
    await userEvent.clear(screen.getByLabelText('Provider guarantee (SOL)'))
    await userEvent.type(screen.getByLabelText('Provider guarantee (SOL)'), '0.1')
    await userEvent.click(screen.getByRole('button', { name: /Create deal/ }))

    expect(openDeal).toHaveBeenCalledWith(expect.objectContaining({
      connection, payer: new PublicKey(WALLET), sendTransaction, config, recipient: PROVIDER, amountLamports: 500_000_000n,
      providerStakeLamports: 100_000_000n, durationSeconds: 10, checkIntervalSeconds: 2, minUptimeBps: 9_900,
    }))
    expect(await screen.findByTestId('deal-verdict')).toHaveTextContent(/Monitoring · \d+s left/)
    expect(await screen.findByTestId('deal-counters')).toHaveTextContent('3 up · 0 down · 7 unobserved / 10 rounds')
    expect(screen.getByTestId('deal-projection')).toHaveTextContent('7 more UP rounds needed')

    expect(await screen.findByText('SLA met · escrow paid to recipient', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByTestId('deal-counters')).toHaveTextContent('10 up · 0 down · 0 unobserved / 10 rounds')
    expect(readOutcome).toHaveBeenCalledWith(connection, new PublicKey(DEAL))
    await waitFor(() => expect(screen.getByTestId('recipient-balance')).toHaveTextContent('0.5 SOL'))
    expect(screen.getByText('5vXy12...defSIG')).toBeInTheDocument()
  })

  it('shows a breach decided by the program', async () => {
    vi.mocked(readDeal).mockResolvedValue(null)
    vi.mocked(readOutcome).mockResolvedValue({
      signature: 'sig', outcome: { cancelled: false, paidToRecipient: false, upChecks: 7, downChecks: 2, totalRounds: 10, minUptimeBps: 9_900, payoutLamports: 1n },
    })
    await createDeal()
    expect(await screen.findByText('SLA breached · escrow paid to payer')).toBeInTheDocument()
    expect(screen.getByTestId('deal-counters')).toHaveTextContent('7 up · 2 down · 1 unobserved / 10 rounds')
  })

  it('validates the terms before sending', async () => {
    await ready()
    const submit = () => userEvent.click(screen.getByRole('button', { name: /Create deal/ }))
    const set = async (label: string, value: string) => {
      await userEvent.clear(screen.getByLabelText(label))
      if (value) await userEvent.type(screen.getByLabelText(label), value)
    }

    await set('Minimum uptime (%)', '0')
    await submit()
    expect(screen.getByText('Enter a minimum uptime between 0.01% and 100%.')).toBeInTheDocument()
    await set('Check interval (seconds)', '3')
    await submit()
    expect(screen.getByText('The check interval must divide the window evenly.')).toBeInTheDocument()
    await set('Window (seconds)', '0')
    await submit()
    expect(screen.getByText('Enter a window of 1 to 3600 seconds.')).toBeInTheDocument()
    await set('Provider guarantee (SOL)', '-1')
    await submit()
    expect(screen.getByText('Enter a provider guarantee of 0 SOL or more.')).toBeInTheDocument()
    await set('Payment (SOL)', '')
    await submit()
    expect(screen.getByText('Enter a payment in SOL.')).toBeInTheDocument()
    expect(openDeal).not.toHaveBeenCalled()
  })

  it('shows why a deal could not be created', async () => {
    vi.mocked(openDeal).mockRejectedValue(new Error('Deal names oracle X'))
    await ready()
    await userEvent.click(screen.getByRole('button', { name: /Create deal/ }))
    expect(await screen.findByText('Deal names oracle X')).toBeInTheDocument()
    expect(screen.queryByTestId('deal-verdict')).not.toBeInTheDocument()
  })

  it('blocks deal creation when the app and the service use different clusters', async () => {
    cluster.url = 'https://api.devnet.solana.com'
    renderAt(<UptimeDealPage />)
    const alert = await screen.findByText(/reports on http:\/\/127\.0\.0\.1:8899, but this app is connected to https:\/\/api\.devnet\.solana\.com/)
    expect(alert).toHaveTextContent('VITE_SOLANA_RPC_URL=http://127.0.0.1:8899')
    expect(screen.getByRole('button', { name: /Create deal/ })).toBeDisabled()
  })

  it('does not warn when localhost and 127.0.0.1 are the same cluster', async () => {
    await ready()
    expect(screen.queryByText(/VITE_SOLANA_RPC_URL/)).not.toBeInTheDocument()
  })

  it('lets the recipient accept a deal that waits for its guarantee', async () => {
    connect(new PublicKey(PROVIDER))
    vi.mocked(readDeal).mockResolvedValue(chainDeal({ payer: WALLET, recipient: PROVIDER, active: false, startsAt: null, providerStakeLamports: 100_000_000n }))
    await createDeal()
    expect(await screen.findByText('Waiting for the provider to lock its guarantee')).toBeInTheDocument()
    expect(screen.getByText('Lock 0.1 SOL to start the window')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel deal' })).not.toBeInTheDocument()

    expect(screen.getByTestId('deal-oracle')).toHaveTextContent('DGT7vw...56GjDd')
    expect(screen.queryByTestId('foreign-oracle')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Accept and lock guarantee' }))
    expect(acceptDeal).toHaveBeenCalledWith({
      connection, sendTransaction, programId: new PublicKey(config.programId), deal: new PublicKey(DEAL), recipient: new PublicKey(PROVIDER),
    })
  })

  it('warns the provider when the deal names an oracle other than this service', async () => {
    connect(new PublicKey(PROVIDER))
    vi.mocked(readDeal).mockResolvedValue(chainDeal({ oracle: WALLET, active: false, startsAt: null, providerStakeLamports: 1n }))
    await createDeal()
    expect(await screen.findByTestId('foreign-oracle')).toHaveTextContent(/names oracle .* not this service's monitor/)
  })

  it('lets the payer cancel a deal the provider has not accepted', async () => {
    vi.mocked(readDeal).mockResolvedValue(chainDeal({ active: false, startsAt: null, providerStakeLamports: 1n }))
    await createDeal()
    expect(await screen.findByRole('button', { name: 'Cancel deal' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Accept and lock guarantee' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Cancel deal' }))
    expect(cancelDeal).toHaveBeenCalledWith({
      connection, sendTransaction, programId: new PublicKey(config.programId), deal: new PublicKey(DEAL), payer: new PublicKey(WALLET),
    })
  })

  it('lets any wallet settle once the program opens settlement', async () => {
    connect(new PublicKey(ORACLE)) // neither party
    vi.mocked(readDeal).mockResolvedValue(chainDeal({ startsAt: nowSeconds() - 30, upChecks: 10 }))
    await createDeal()
    expect(await screen.findByText('Ready to settle on chain')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Settle now' }))
    expect(settleDeal).toHaveBeenCalledWith({
      connection, sendTransaction, programId: new PublicKey(config.programId), deal: new PublicKey(DEAL),
      caller: new PublicKey(ORACLE), payer: new PublicKey(WALLET), recipient: new PublicKey(PROVIDER),
    })
  })

  it('does not offer settlement while the window or the observation grace is running', async () => {
    vi.mocked(readDeal).mockResolvedValue(chainDeal({ startsAt: nowSeconds() - 15 }))
    await createDeal()
    expect(await screen.findByText(/Collecting the last observations/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Settle now' })).not.toBeInTheDocument()
  })

  it('shows why settling failed', async () => {
    vi.mocked(settleDeal).mockRejectedValue(new Error('Transaction failed: SettleTooEarly'))
    vi.mocked(readDeal).mockResolvedValue(chainDeal({ startsAt: nowSeconds() - 30 }))
    await createDeal()
    await userEvent.click(await screen.findByRole('button', { name: 'Settle now' }))
    expect(await screen.findByText('Transaction failed: SettleTooEarly')).toBeInTheDocument()
  })

  it('shows a cancelled deal', async () => {
    vi.mocked(readDeal).mockResolvedValue(null)
    vi.mocked(readOutcome).mockResolvedValue({ signature: 'sig', outcome: { cancelled: true } })
    await createDeal()
    expect(await screen.findByText('Cancelled · payment returned to payer')).toBeInTheDocument()
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
  const start = Date.parse('2026-10-03T12:00:01Z') / 1000
  const now = Date.parse('2026-10-03T12:00:05Z')
  const at = (overrides: Partial<OnChainDeal> = {}) => chainDeal({ startsAt: start, ...overrides })
  const settled = (paidToRecipient: boolean) => ({ signature: 's', outcome: { cancelled: false as const, paidToRecipient, upChecks: 1, downChecks: 0, totalRounds: 1, minUptimeBps: 1, payoutLamports: 1n } })

  it.each([
    [at(), null, now, 'Monitoring · 6s left'],
    [at(), null, now + 6_000, 'Collecting the last observations · settlement opens in 10s'],
    [at(), null, now + 16_000, 'Ready to settle on chain'],
    [at({ active: false, startsAt: null }), null, now, 'Waiting for the provider to lock its guarantee'],
    [null, null, now, 'Reading the deal from chain…'],
    [null, settled(true), now, 'SLA met · escrow paid to recipient'],
    [null, settled(false), now, 'SLA breached · escrow paid to payer'],
    [null, { signature: 's', outcome: { cancelled: true as const } }, now, 'Cancelled · payment returned to payer'],
    [null, 'unknown' as const, now, 'Closed on chain'],
  ])('describes %#', (deal, closed, time, text) => {
    expect(dealVerdict(deal, closed, time)).toBe(text)
  })
})

describe('display helpers', () => {
  it('summarises the counters', () => {
    expect(counterLine(9, 1, 10)).toBe('9 up · 1 down · 0 unobserved / 10 rounds')
    expect(counterLine(0, 0, 5)).toBe('0 up · 0 down · 5 unobserved / 5 rounds')
  })

  it('formats thresholds', () => {
    expect(formatBps(9_900)).toBe('99%')
    expect(formatBps(9_950)).toBe('99.5%')
    expect(formatBps(1)).toBe('0.01%')
  })

  it('projects whether the threshold can still be reached', () => {
    expect(projection(chainDeal({ upChecks: 10 }))).toBe('Threshold already reached')
    expect(projection(chainDeal({ upChecks: 2, downChecks: 1 }))).toBe('Threshold can no longer be reached')
    expect(projection(chainDeal({ upChecks: 2 }))).toBe('8 more UP rounds needed')
  })

  it('knows when the program opens settlement', () => {
    expect(settleOpensAt(chainDeal({ startsAt: 1_000, durationSeconds: 60 }))).toBe(1_070_000)
    expect(settleOpensAt(chainDeal({ active: false, startsAt: null }))).toBeNull()
  })

  it('formats lamports as SOL', () => {
    expect(formatLamports(null)).toBe('—')
    expect(formatLamports(0)).toBe('0 SOL')
    expect(formatLamports(500_000_000)).toBe('0.5 SOL')
    expect(formatLamports(1_499_995_000n)).toBe('1.499995 SOL')
  })
})
