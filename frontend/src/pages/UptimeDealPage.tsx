import { useEffect, useState, type FormEvent } from 'react'
import { ArrowRight, Coins, Droplets, LockKeyhole, Server, ShieldCheck, Timer } from 'lucide-react'
import { useConnection, useWallet } from '@solana/wallet-adapter-react'
import { WalletMultiButton } from '@solana/wallet-adapter-react-ui'
import { LAMPORTS_PER_SOL, PublicKey } from '@solana/web3.js'
import { ErrorState } from '../components/UI'
import { useAsyncData } from '../hooks/useAsyncData'
import { useBalance } from '../hooks/useBalance'
import { useNow } from '../hooks/useNow'
import { sameCluster, SOLANA_RPC_URL } from '../config/solana'
import { shortAddress } from '../lib/format'
import { dealApi, type TrackedDeal } from '../services/deal/dealApi'
import { CANCEL_TIMEOUT_SECONDS } from '../services/deal/dealProgram'
import { cancelDeal, openDeal, requestAirdrop } from '../services/deal/dealService'
import { uptimeService, type UptimeServiceState } from '../services/uptime/uptimeService'

const AIRDROP_LAMPORTS = 2 * LAMPORTS_PER_SOL

/**
 * Formats lamports as SOL with up to 9 decimals.
 *
 * @param lamports the amount, or null when unknown
 * @returns e.g. "0.5 SOL", or "—" when unknown
 */
export function formatLamports(lamports: number | null): string {
  if (lamports === null) return '—'
  return `${new Intl.NumberFormat('en-US', { maximumFractionDigits: 9 }).format(lamports / LAMPORTS_PER_SOL)} SOL`
}

/**
 * Describes where a tracked deal stands.
 *
 * @param deal the tracked deal
 * @param now the current time in ms
 * @returns a short status line for the deal panel
 */
export function dealVerdict(deal: TrackedDeal, now: number): string {
  if (deal.status === 'CANCELLED') return 'Cancelled · escrow returned to payer'
  if (deal.status === 'FAILED') return `Settlement failed: ${deal.error ?? 'unknown error'}`
  if (deal.status === 'SETTLED') {
    if (deal.paidToRecipient === true) return 'Paid to recipient'
    if (deal.paidToRecipient === false) return 'Refunded to payer'
    return 'Settled'
  }
  const left = Math.ceil((Date.parse(deal.endsAt) - now) / 1000)
  if (Date.parse(deal.startsAt) > now) return 'Window starts in a moment'
  return left > 0 ? `Measuring uptime · ${left}s left` : 'Settling on chain…'
}

/**
 * Tells when the payer may reclaim a deal's escrow.
 *
 * @param deal the tracked deal
 * @returns the time in ms from which `cancel_deal` is accepted
 */
export function reclaimableAt(deal: TrackedDeal): number {
  return Date.parse(deal.endsAt) + CANCEL_TIMEOUT_SECONDS * 1000
}

/** Creates a real on-chain uptime deal and follows it until the uptime service settles it. */
export function UptimeDealPage() {
  const { connection } = useConnection()
  const { publicKey, sendTransaction } = useWallet()
  const now = useNow()
  const { data: config, error: configError, reload: reloadConfig } = useAsyncData(() => dealApi.getConfig(), [])
  const [serviceState, setServiceState] = useState<UptimeServiceState | null>(null)
  const [recipient, setRecipient] = useState('')
  const [amountSol, setAmountSol] = useState('0.5')
  const [durationSeconds, setDurationSeconds] = useState('10')
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [deal, setDeal] = useState<TrackedDeal | null>(null)
  const wallet = useBalance(connection, publicKey?.toBase58())
  const recipientBalance = useBalance(connection, deal?.recipient)

  useEffect(() => {
    const load = () => uptimeService.getState().then(setServiceState, () => setServiceState(null))
    void load()
    const interval = window.setInterval(load, 2000)
    return () => window.clearInterval(interval)
  }, [])

  const dealAddress = deal?.address
  const dealDone = deal?.status === 'SETTLED' || deal?.status === 'FAILED' || deal?.status === 'CANCELLED'
  useEffect(() => {
    if (!dealAddress || dealDone) return
    const load = () => dealApi.get(dealAddress).then(setDeal, () => undefined)
    void load()
    const interval = window.setInterval(load, 1000)
    return () => window.clearInterval(interval)
  }, [dealAddress, dealDone])

  useEffect(() => {
    if (dealDone) {
      void recipientBalance.refresh()
      void wallet.refresh()
    }
  }, [dealDone]) // eslint-disable-line react-hooks/exhaustive-deps

  const clusterMismatch = config !== undefined && config !== null && !sameCluster(config.rpcUrl, SOLANA_RPC_URL)
  const isPayer = Boolean(deal && publicKey && deal.payer === publicKey.toBase58())
  const stuck = deal !== null && (deal.status === 'FAILED' || (deal.status === 'ACTIVE' && now >= reclaimableAt(deal)))
  const reclaimAt = deal ? reclaimableAt(deal) : null
  const canReclaim = reclaimAt !== null && now >= reclaimAt

  async function run(label: string, action: () => Promise<void>) {
    setBusy(label)
    setError(null)
    try {
      await action()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Something went wrong.')
    } finally {
      setBusy(null)
    }
  }

  function airdrop() {
    if (!publicKey) return
    void run('Requesting airdrop', async () => {
      await requestAirdrop(connection, publicKey, AIRDROP_LAMPORTS)
      await wallet.refresh()
    })
  }

  function toggleService() {
    void run(serviceState === 'DOWN' ? 'Restoring service' : 'Stopping service', async () => {
      setServiceState(await dealApi.setServiceUp(serviceState === 'DOWN'))
    })
  }

  function reclaim() {
    if (!publicKey || !config || !deal) return
    void run('Reclaiming escrow', async () => {
      await cancelDeal({ connection, payer: publicKey, sendTransaction, programId: new PublicKey(config.programId), deal: new PublicKey(deal.address) })
      setDeal({ ...deal, status: 'CANCELLED' })
      await wallet.refresh()
    })
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!publicKey || !config || clusterMismatch) return
    const sol = Number(amountSol)
    const seconds = Number(durationSeconds)
    if (!Number.isFinite(sol) || sol <= 0) { setError('Enter an amount in SOL.'); return }
    if (!Number.isInteger(seconds) || seconds < 1 || seconds > 3600) { setError('Enter a window of 1 to 3600 seconds.'); return }
    void run('Creating deal', async () => {
      setDeal(null)
      setDeal(await openDeal({
        connection, payer: publicKey, sendTransaction, config, recipient: recipient.trim(),
        amountLamports: BigInt(Math.round(sol * LAMPORTS_PER_SOL)), durationSeconds: seconds,
      }))
      await wallet.refresh()
    })
  }

  const measured = deal?.upSeconds != null && deal.totalSeconds ? `${deal.upSeconds}/${deal.totalSeconds} s up (${(deal.upSeconds / deal.totalSeconds * 100).toFixed(1)}%)` : '—'

  return <div className="page-stack">
    <div className="page-heading create-heading"><div>
      <div className="eyebrow"><span className="eyebrow-line" /> ON-CHAIN DEAL <span className="eyebrow-slash">/</span> UPTIME &gt; 99%</div>
      <h1>Uptime deal</h1>
      <p>Lock SOL for a recipient. If the uptime service stays up more than 99% of the window, the program pays the recipient; otherwise it refunds you.</p>
    </div></div>

    {configError && <ErrorState message={`Uptime service unavailable: ${configError}`} retry={() => void reloadConfig()} />}
    {config && clusterMismatch && <ErrorState message={`The uptime service settles on ${config.rpcUrl}, but this app is connected to ${SOLANA_RPC_URL}. Set VITE_SOLANA_RPC_URL=${config.rpcUrl} and restart the frontend.`} />}
    {error && <ErrorState message={error} />}

    <div className="form-layout">
      <form onSubmit={submit} className="surface form-surface" noValidate aria-label="Create uptime deal">
        <div className="form-section"><div className="form-section-heading"><span className="form-step">01</span><div><h2>Your wallet</h2><p>The payer signs create_deal and funds the escrow.</p></div></div>
          {publicKey ? <div className="agreement-list">
            <div><span>Payer</span><strong title={publicKey.toBase58()}>{shortAddress(publicKey.toBase58(), 6, 6)}</strong></div>
            <div><span>Wallet balance</span><strong data-testid="wallet-balance">{formatLamports(wallet.lamports)}</strong></div>
            <div><span>Need SOL on localnet or devnet?</span><button type="button" className="button" onClick={airdrop} disabled={Boolean(busy)}><Droplets size={15} /> Airdrop 2 SOL</button></div>
          </div> : <div className="agreement-list"><div><span>Connect a wallet to create a deal.</span><WalletMultiButton /></div></div>}
        </div>

        <div className="form-section"><div className="form-section-heading"><span className="form-step">02</span><div><h2>Deal terms</h2><p>The uptime service is the oracle; it settles when the window ends.</p></div></div>
          <div className="field"><label htmlFor="recipient">Recipient address</label><input id="recipient" value={recipient} onChange={(e) => setRecipient(e.target.value)} placeholder="Solana wallet paid when uptime is above 99%" /><small>Receives the escrow if the service stays up.</small></div>
          <div className="field-grid">
            <div className="field"><label htmlFor="amount">Amount (SOL)</label><input id="amount" type="number" min="0.001" step="0.001" value={amountSol} onChange={(e) => setAmountSol(e.target.value)} /><small>At least 0.001 SOL.</small></div>
            <div className="field"><label htmlFor="duration">Window (seconds)</label><input id="duration" type="number" min="1" max="3600" step="1" value={durationSeconds} onChange={(e) => setDurationSeconds(e.target.value)} /><small>Uptime is measured per second over this window.</small></div>
          </div>
        </div>

        <div className="form-submit"><button className="button button-primary button-large" type="submit" disabled={!publicKey || !config || clusterMismatch || Boolean(busy)}>{busy ?? 'Create deal'}<ArrowRight size={17} /></button></div>
      </form>

      <aside className="summary-column">
        <section className="surface summary-card" aria-label="Uptime service">
          <div className="summary-icon"><Server size={21} /></div>
          <h2>Uptime service</h2>
          <p className="summary-intro">Its per-second health history decides the deal.</p>
          <div className="summary-list">
            <div><span>Current state</span><strong data-testid="service-state">{serviceState ?? 'Unavailable'}</strong></div>
            <div><span>Oracle</span><strong title={config?.oracle}>{config ? shortAddress(config.oracle, 6, 6) : '—'}</strong></div>
          </div>
          <button type="button" className="button settle-button" onClick={toggleService} disabled={!serviceState || Boolean(busy)}>{serviceState === 'DOWN' ? 'Restore service' : 'Simulate outage'}</button>
        </section>

        {deal && <section className="surface summary-card" aria-label="Deal">
          <div className="summary-icon">{dealDone ? <ShieldCheck size={21} /> : <Timer size={21} />}</div>
          <h2>Deal</h2>
          <p className="summary-intro" role="status" data-testid="deal-verdict">{dealVerdict(deal, now)}</p>
          <div className="summary-list">
            <div><span>Address</span><strong title={deal.address}>{shortAddress(deal.address, 6, 6)}</strong></div>
            <div><span>Window</span><strong>{deal.durationSeconds}s</strong></div>
            <div><span>Measured</span><strong data-testid="deal-measured">{measured}</strong></div>
            <div><span>Recipient</span><strong title={deal.recipient}>{shortAddress(deal.recipient, 6, 6)}</strong></div>
            <div><span><Coins size={16} /> Recipient balance</span><strong data-testid="recipient-balance">{formatLamports(recipientBalance.lamports)}</strong></div>
            {deal.signature && <div><span>Settlement tx</span><strong title={deal.signature}>{shortAddress(deal.signature, 6, 6)}</strong></div>}
          </div>
          {stuck && isPayer && <div className="agreement-escrow">
            <span>{canReclaim ? 'Escrow can be reclaimed' : `Reclaim available ${new Date(reclaimAt ?? 0).toLocaleTimeString()}`}</span>
            <button type="button" className="button settle-button" onClick={reclaim} disabled={!canReclaim || Boolean(busy)}>Reclaim escrow</button>
          </div>}
          <div className="agreement-escrow"><span><LockKeyhole size={16} /> Escrow</span><strong>{formatLamports(deal.amountLamports)}</strong></div>
        </section>}
      </aside>
    </div>
  </div>
}
