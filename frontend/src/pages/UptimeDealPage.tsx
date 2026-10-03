import { useEffect, useState, type FormEvent } from 'react'
import { ArrowRight, Coins, Copy, Droplets, Handshake, LockKeyhole, Server, ShieldCheck, Timer } from 'lucide-react'
import { useSearchParams } from 'react-router-dom'
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
import { acceptDeal, cancelDeal, openDeal, requestAirdrop } from '../services/deal/dealService'
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
  if (deal.status === 'CANCELLED') return 'Cancelled · deposits returned'
  if (deal.status === 'FAILED') return `Settlement failed: ${deal.error ?? 'unknown error'}`
  if (deal.status === 'SETTLED') {
    if (deal.paidToRecipient === true) return 'Paid to recipient'
    if (deal.paidToRecipient === false) return 'Refunded to payer'
    return 'Settled'
  }
  if (deal.status === 'PROPOSED' || !deal.startsAt || !deal.endsAt) {
    return Date.parse(deal.acceptDeadline) > now ? 'Waiting for the provider to accept' : 'Proposal expired · withdraw it to get your payment back'
  }
  const left = Math.ceil((Date.parse(deal.endsAt) - now) / 1000)
  if (Date.parse(deal.startsAt) > now) return 'Window starts in a moment'
  return left > 0 ? `Measuring uptime · ${left}s left` : 'Settling on chain…'
}

/**
 * Tells when a party may reclaim the deposits of an accepted deal the oracle didn't settle.
 *
 * @param deal the tracked deal
 * @returns the time in ms from which `cancel_deal` is accepted; 0 when the deal has no window, since a
 *   proposal can be cancelled at any time
 */
export function reclaimableAt(deal: TrackedDeal): number {
  return deal.endsAt ? Date.parse(deal.endsAt) + CANCEL_TIMEOUT_SECONDS * 1000 : 0
}

/**
 * Builds the link a payer sends to the provider so it can review and accept a proposal.
 *
 * @param address the deal address
 * @returns an absolute URL of this page with `?deal=<address>`
 */
export function dealLink(address: string): string {
  return `${window.location.origin}/deal?deal=${encodeURIComponent(address)}`
}

/** Writes text to the clipboard, ignoring browsers that refuse it. */
async function copy(text: string): Promise<void> {
  try { await navigator.clipboard.writeText(text) } catch { /* nothing to copy into */ }
}

/**
 * Proposes a real on-chain uptime deal, lets the provider accept it from a shared link, and follows it until
 * the uptime service settles it.
 */
export function UptimeDealPage() {
  const { connection } = useConnection()
  const { publicKey, sendTransaction } = useWallet()
  const now = useNow()
  const [searchParams, setSearchParams] = useSearchParams()
  const linkedDeal = searchParams.get('deal')
  const { data: config, error: configError, reload: reloadConfig } = useAsyncData(() => dealApi.getConfig(), [])
  const [serviceState, setServiceState] = useState<UptimeServiceState | null>(null)
  const [recipient, setRecipient] = useState('')
  const [amountSol, setAmountSol] = useState('0.5')
  const [guaranteeSol, setGuaranteeSol] = useState('0.5')
  const [durationSeconds, setDurationSeconds] = useState('10')
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [deal, setDeal] = useState<TrackedDeal | null>(null)
  const [tracked, setTracked] = useState<TrackedDeal[]>([])
  const wallet = useBalance(connection, publicKey?.toBase58())
  const recipientBalance = useBalance(connection, deal?.recipient)

  useEffect(() => {
    const load = () => uptimeService.getState().then(setServiceState, () => setServiceState(null))
    void load()
    const interval = window.setInterval(load, 2000)
    return () => window.clearInterval(interval)
  }, [])

  // A provider finds the proposals addressed to its wallet here, without reloading the page: a reload
  // gives the burner wallet a new key, which would no longer be the deal's provider.
  const me = publicKey?.toBase58()
  useEffect(() => {
    if (!me) return
    const load = () => dealApi.list().then(setTracked, () => undefined)
    void load()
    const interval = window.setInterval(load, 2000)
    return () => window.clearInterval(interval)
  }, [me])

  useEffect(() => {
    if (!linkedDeal) return
    let ignore = false
    dealApi.get(linkedDeal).then(
      (found) => { if (!ignore) setDeal(found) },
      (cause: unknown) => { if (!ignore) setError(`Deal ${linkedDeal} could not be loaded: ${cause instanceof Error ? cause.message : 'unknown error'}`) },
    )
    return () => { ignore = true }
  }, [linkedDeal])

  const dealAddress = deal?.address
  const dealDone = deal?.status === 'SETTLED' || deal?.status === 'FAILED' || deal?.status === 'CANCELLED'
  useEffect(() => {
    if (!dealAddress || dealDone) return
    const load = () => dealApi.get(dealAddress).then(setDeal, () => undefined)
    void load()
    const interval = window.setInterval(load, 1000)
    return () => window.clearInterval(interval)
  }, [dealAddress, dealDone])

  const dealStatus = deal?.status
  useEffect(() => {
    if (dealDone || dealStatus === 'ACTIVE') {
      void recipientBalance.refresh()
      void wallet.refresh()
    }
  }, [dealDone, dealStatus]) // eslint-disable-line react-hooks/exhaustive-deps

  const clusterMismatch = config !== undefined && config !== null && !sameCluster(config.rpcUrl, SOLANA_RPC_URL)
  const incoming = tracked.filter((d) => d.status === 'PROPOSED' && d.recipient === me && d.address !== deal?.address && Date.parse(d.acceptDeadline) > now)
  const isPayer = Boolean(deal && me && deal.payer === me)
  const isRecipient = Boolean(deal && me && deal.recipient === me)
  const proposed = deal?.status === 'PROPOSED'
  const acceptOpen = Boolean(deal && proposed && Date.parse(deal.acceptDeadline) > now)
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

  function cancel(label: string) {
    if (!publicKey || !config || !deal) return
    void run(label, async () => {
      await cancelDeal({ connection, signer: publicKey, sendTransaction, programId: new PublicKey(config.programId), deal })
      setDeal({ ...deal, status: 'CANCELLED' })
      await wallet.refresh()
    })
  }

  function accept() {
    if (!publicKey || !config || !deal) return
    void run('Accepting deal', async () => {
      await acceptDeal({ connection, recipient: publicKey, sendTransaction, config, deal })
      setDeal(await dealApi.get(deal.address))
      await wallet.refresh()
    })
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!publicKey || !config || clusterMismatch) return
    const sol = Number(amountSol)
    const guarantee = Number(guaranteeSol)
    const seconds = Number(durationSeconds)
    if (!Number.isFinite(sol) || sol <= 0) { setError('Enter a payment in SOL.'); return }
    if (!Number.isFinite(guarantee) || guarantee <= 0) { setError('Enter a provider guarantee in SOL.'); return }
    if (!Number.isInteger(seconds) || seconds < 1 || seconds > 3600) { setError('Enter a window of 1 to 3600 seconds.'); return }
    void run('Proposing deal', async () => {
      setDeal(null)
      const proposal = await openDeal({
        connection, payer: publicKey, sendTransaction, config, recipient: recipient.trim(),
        amountLamports: BigInt(Math.round(sol * LAMPORTS_PER_SOL)),
        guaranteeLamports: BigInt(Math.round(guarantee * LAMPORTS_PER_SOL)),
        durationSeconds: seconds,
      })
      setDeal(proposal)
      setSearchParams({ deal: proposal.address })
      await wallet.refresh()
    })
  }

  const measured = deal?.upSeconds != null && deal.totalSeconds ? `${deal.upSeconds}/${deal.totalSeconds} s up (${(deal.upSeconds / deal.totalSeconds * 100).toFixed(1)}%)` : '—'

  return <div className="page-stack">
    <div className="page-heading create-heading"><div>
      <div className="eyebrow"><span className="eyebrow-line" /> ON-CHAIN DEAL <span className="eyebrow-slash">/</span> UPTIME &gt; 99%</div>
      <h1>Uptime deal</h1>
      <p>Propose a deal to a provider and lock your payment. The provider accepts by locking a guarantee, which starts the window. If the uptime service stays up more than 99% of it, the provider receives both; otherwise you do.</p>
    </div></div>

    {configError && <ErrorState message={`Uptime service unavailable: ${configError}`} retry={() => void reloadConfig()} />}
    {config && clusterMismatch && <ErrorState message={`The uptime service settles on ${config.rpcUrl}, but this app is connected to ${SOLANA_RPC_URL}. Set VITE_SOLANA_RPC_URL=${config.rpcUrl} and restart the frontend.`} />}
    {error && <ErrorState message={error} />}

    <div className="form-layout">
      <form onSubmit={submit} className="surface form-surface" noValidate aria-label="Propose uptime deal">
        <div className="form-section"><div className="form-section-heading"><span className="form-step">01</span><div><h2>Your wallet</h2><p>The payer signs create_deal and locks its payment; the provider signs accept_deal and locks its guarantee.</p></div></div>
          {publicKey ? <div className="agreement-list">
            <div><span>Wallet</span><strong title={publicKey.toBase58()} data-testid="wallet-address">{shortAddress(publicKey.toBase58(), 6, 6)}</strong></div>
            <div><span>Share it with the payer if you are the provider</span><button type="button" className="button" onClick={() => void copy(publicKey.toBase58())}><Copy size={15} /> Copy my address</button></div>
            <div><span>Wallet balance</span><strong data-testid="wallet-balance">{formatLamports(wallet.lamports)}</strong></div>
            <div><span>Need SOL on localnet or devnet?</span><button type="button" className="button" onClick={airdrop} disabled={Boolean(busy)}><Droplets size={15} /> Airdrop 2 SOL</button></div>
          </div> : <div className="agreement-list"><div><span>Connect a wallet to propose or accept a deal.</span><WalletMultiButton /></div></div>}
        </div>

        <div className="form-section"><div className="form-section-heading"><span className="form-step">02</span><div><h2>Deal terms</h2><p>The uptime service is the oracle; it settles when the window ends.</p></div></div>
          <div className="field"><label htmlFor="recipient">Provider address</label><input id="recipient" value={recipient} onChange={(e) => setRecipient(e.target.value)} placeholder="Solana wallet of the provider" /><small>Must accept the deal; receives both deposits if the service stays up.</small></div>
          <div className="field-grid">
            <div className="field"><label htmlFor="amount">Payment (SOL)</label><input id="amount" type="number" min="0.001" step="0.001" value={amountSol} onChange={(e) => setAmountSol(e.target.value)} /><small>You lock it now. At least 0.001 SOL.</small></div>
            <div className="field"><label htmlFor="guarantee">Provider guarantee (SOL)</label><input id="guarantee" type="number" min="0.001" step="0.001" value={guaranteeSol} onChange={(e) => setGuaranteeSol(e.target.value)} /><small>The provider locks it on acceptance and loses it on a breach.</small></div>
          </div>
          <div className="field"><label htmlFor="duration">Window (seconds)</label><input id="duration" type="number" min="1" max="3600" step="1" value={durationSeconds} onChange={(e) => setDurationSeconds(e.target.value)} /><small>Starts when the provider accepts. Uptime is measured per second.</small></div>
        </div>

        <div className="form-submit"><button className="button button-primary button-large" type="submit" disabled={!publicKey || !config || clusterMismatch || Boolean(busy)}>{busy ?? 'Propose deal'}<ArrowRight size={17} /></button></div>
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

        {incoming.length > 0 && <section className="surface summary-card" aria-label="Proposals for you">
          <div className="summary-icon"><Handshake size={21} /></div>
          <h2>Proposals for you</h2>
          <p className="summary-intro">Payers proposed these deals to your wallet. Review one to accept or reject it.</p>
          {incoming.map((offer) => <div className="agreement-escrow" key={offer.address}>
            <span title={offer.payer}>{shortAddress(offer.payer, 6, 6)} pays {formatLamports(offer.amountLamports)} · you lock {formatLamports(offer.guaranteeLamports)} · {offer.durationSeconds}s</span>
            <button type="button" className="button" onClick={() => { setError(null); setSearchParams({ deal: offer.address }) }}>Review</button>
          </div>)}
        </section>}

        {deal && <section className="surface summary-card" aria-label="Deal">
          <div className="summary-icon">{dealDone ? <ShieldCheck size={21} /> : proposed ? <Handshake size={21} /> : <Timer size={21} />}</div>
          <h2>Deal</h2>
          <p className="summary-intro" role="status" data-testid="deal-verdict">{dealVerdict(deal, now)}</p>
          <div className="summary-list">
            <div><span>Address</span><strong title={deal.address}>{shortAddress(deal.address, 6, 6)}</strong></div>
            <div><span>Window</span><strong>{deal.durationSeconds}s</strong></div>
            {proposed && <div><span>Accept by</span><strong>{new Date(deal.acceptDeadline).toLocaleString()}</strong></div>}
            <div><span>Measured</span><strong data-testid="deal-measured">{measured}</strong></div>
            <div><span>Provider</span><strong title={deal.recipient}>{shortAddress(deal.recipient, 6, 6)}</strong></div>
            <div><span><Coins size={16} /> Provider balance</span><strong data-testid="recipient-balance">{formatLamports(recipientBalance.lamports)}</strong></div>
            <div><span>Payment</span><strong data-testid="deal-payment">{formatLamports(deal.amountLamports)}</strong></div>
            <div><span>Provider guarantee</span><strong data-testid="deal-guarantee">{formatLamports(deal.guaranteeLamports)}</strong></div>
            {deal.signature && <div><span>Settlement tx</span><strong title={deal.signature}>{shortAddress(deal.signature, 6, 6)}</strong></div>}
          </div>
          {proposed && isPayer && <div className="agreement-escrow">
            <span>The provider sees it under “Proposals for you”, or opens this link</span>
            <button type="button" className="button" onClick={() => void copy(dealLink(deal.address))}><Copy size={15} /> Copy link</button>
          </div>}
          {proposed && isRecipient && <div className="agreement-escrow">
            <span>{acceptOpen ? 'You are the provider of this deal' : 'The proposal has expired'}</span>
            <button type="button" className="button button-primary settle-button" onClick={accept} disabled={!acceptOpen || Boolean(busy)}>Accept and lock {formatLamports(deal.guaranteeLamports)}</button>
            <button type="button" className="button settle-button" onClick={() => cancel('Rejecting deal')} disabled={Boolean(busy)}>Reject</button>
          </div>}
          {proposed && isPayer && <div className="agreement-escrow">
            <span>Not accepted yet</span>
            <button type="button" className="button settle-button" onClick={() => cancel('Withdrawing proposal')} disabled={Boolean(busy)}>Withdraw proposal</button>
          </div>}
          {stuck && (isPayer || isRecipient) && <div className="agreement-escrow">
            <span>{canReclaim ? 'Deposits can be reclaimed' : `Reclaim available ${new Date(reclaimAt ?? 0).toLocaleTimeString()}`}</span>
            <button type="button" className="button settle-button" onClick={() => cancel('Reclaiming deposits')} disabled={!canReclaim || Boolean(busy)}>Reclaim deposits</button>
          </div>}
          <div className="agreement-escrow"><span><LockKeyhole size={16} /> Escrow</span><strong data-testid="deal-escrow">{formatLamports(deal.amountLamports + (proposed ? 0 : deal.guaranteeLamports))}</strong></div>
        </section>}
      </aside>
    </div>
  </div>
}
