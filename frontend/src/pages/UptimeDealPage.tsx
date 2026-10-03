import { useEffect, useState, type FormEvent } from 'react'
import { ArrowRight, Coins, Droplets, Gavel, LockKeyhole, Server, ShieldCheck, Timer } from 'lucide-react'
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
import { BPS_DENOMINATOR, OBSERVATION_GRACE_SECONDS, requiredUpRounds, totalRounds, type DealOutcome, type OnChainDeal } from '../services/deal/dealProgram'
import { acceptDeal, cancelDeal, openDeal, readDeal, readOutcome, requestAirdrop, settleDeal } from '../services/deal/dealService'
import { uptimeService, type UptimeServiceState } from '../services/uptime/uptimeService'

const AIRDROP_LAMPORTS = 2 * LAMPORTS_PER_SOL

/** How a followed deal was closed: its event and transaction, or 'unknown' when no event was found. */
export type ClosedDeal = { outcome: DealOutcome; signature: string } | 'unknown'

/**
 * Formats lamports as SOL with up to 9 decimals.
 *
 * @param lamports the amount, or null when unknown
 * @returns e.g. "0.5 SOL", or "—" when unknown
 */
export function formatLamports(lamports: number | bigint | null): string {
  if (lamports === null) return '—'
  return `${new Intl.NumberFormat('en-US', { maximumFractionDigits: 9 }).format(Number(lamports) / LAMPORTS_PER_SOL)} SOL`
}

/**
 * Tells when the program opens settlement (and closes observations) for a deal.
 *
 * @param deal the on-chain deal
 * @returns the time in ms, or null while the deal waits for the provider
 */
export function settleOpensAt(deal: OnChainDeal): number | null {
  if (deal.startsAt === null) return null
  return (deal.startsAt + deal.durationSeconds + OBSERVATION_GRACE_SECONDS) * 1000
}

/**
 * Describes where a deal stands, from on-chain state only.
 *
 * @param deal the deal account, or null when not read yet
 * @param closed how the deal was closed, or null while its account exists
 * @param now the current time in ms
 * @returns a short status line for the deal panel
 */
export function dealVerdict(deal: OnChainDeal | null, closed: ClosedDeal | null, now: number): string {
  if (closed === 'unknown') return 'Closed on chain'
  if (closed) {
    if (closed.outcome.cancelled) return 'Cancelled · payment returned to payer'
    return closed.outcome.paidToRecipient ? 'SLA met · escrow paid to recipient' : 'SLA breached · escrow paid to payer'
  }
  if (!deal) return 'Reading the deal from chain…'
  if (deal.startsAt === null) return 'Waiting for the provider to lock its guarantee'
  const endsAt = (deal.startsAt + deal.durationSeconds) * 1000
  if (now < endsAt) return `Monitoring · ${Math.ceil((endsAt - now) / 1000)}s left`
  const opens = settleOpensAt(deal) ?? 0
  if (now < opens) return `Collecting the last observations · settlement opens in ${Math.ceil((opens - now) / 1000)}s`
  return 'Ready to settle on chain'
}

/**
 * Summarises the program's counters.
 *
 * @param up rounds recorded UP
 * @param down rounds recorded DOWN
 * @param total rounds in the window
 * @returns e.g. "9 up · 1 down · 0 unobserved / 10 rounds"
 */
export function counterLine(up: number, down: number, total: number): string {
  return `${up} up · ${down} down · ${total - up - down} unobserved / ${total} rounds`
}

/**
 * Formats a basis-point threshold as a percentage.
 *
 * @param bps the threshold in basis points
 * @returns e.g. "99%" or "99.5%"
 */
export function formatBps(bps: number): string {
  return `${new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(bps / 100)}%`
}

/**
 * An informational projection from the counters. The program alone decides; this only says whether the
 * provider can still reach the threshold if every remaining round is UP.
 *
 * @param deal the on-chain deal
 * @returns a short projection line
 */
export function projection(deal: OnChainDeal): string {
  const required = requiredUpRounds(deal.totalRounds, deal.minUptimeBps)
  if (deal.upChecks >= required) return 'Threshold already reached'
  if (deal.totalRounds - deal.downChecks < required) return 'Threshold can no longer be reached'
  return `${required - deal.upChecks} more UP rounds needed`
}

/** Creates a real on-chain uptime SLA and follows it, from chain state, until the program settles it. */
export function UptimeDealPage() {
  const { connection } = useConnection()
  const { publicKey, sendTransaction } = useWallet()
  const now = useNow()
  const { data: config, error: configError, reload: reloadConfig } = useAsyncData(() => dealApi.getConfig(), [])
  const [serviceState, setServiceState] = useState<UptimeServiceState | null>(null)
  const [recipient, setRecipient] = useState('')
  const [amountSol, setAmountSol] = useState('0.5')
  const [stakeSol, setStakeSol] = useState('0')
  const [durationSeconds, setDurationSeconds] = useState('10')
  const [intervalSeconds, setIntervalSeconds] = useState('1')
  const [minUptimePercent, setMinUptimePercent] = useState('99')
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [address, setAddress] = useState<string | null>(null)
  const [chain, setChain] = useState<OnChainDeal | null>(null)
  const [closed, setClosed] = useState<ClosedDeal | null>(null)
  const [monitor, setMonitor] = useState<TrackedDeal | null>(null)
  const wallet = useBalance(connection, publicKey?.toBase58())
  const recipientBalance = useBalance(connection, chain?.recipient ?? monitor?.recipient)

  useEffect(() => {
    const load = () => uptimeService.getState().then(setServiceState, () => setServiceState(null))
    void load()
    const interval = window.setInterval(load, 2000)
    return () => window.clearInterval(interval)
  }, [])

  // The deal account is the authority: poll it until it closes, then read the program's closing event.
  const programId = config?.programId
  useEffect(() => {
    if (!address || !programId || closed) return
    let cancelled = false
    const load = async () => {
      try {
        const deal = await readDeal(connection, new PublicKey(programId), new PublicKey(address))
        if (cancelled) return
        if (deal) { setChain(deal); return }
        const outcome = await readOutcome(connection, new PublicKey(address))
        if (!cancelled) setClosed(outcome ?? 'unknown')
      } catch { /* keep the last state and retry */ }
    }
    void load()
    const interval = window.setInterval(() => void load(), 1000)
    return () => { cancelled = true; window.clearInterval(interval) }
  }, [address, programId, closed, connection])

  // The service's view is informational: how many observations it sent, and any settlement error.
  useEffect(() => {
    if (!address || closed) return
    const load = () => dealApi.get(address).then(setMonitor, () => undefined)
    void load()
    const interval = window.setInterval(load, 1000)
    return () => window.clearInterval(interval)
  }, [address, closed])

  useEffect(() => {
    if (closed) {
      void recipientBalance.refresh()
      void wallet.refresh()
    }
  }, [closed]) // eslint-disable-line react-hooks/exhaustive-deps

  const clusterMismatch = config !== undefined && config !== null && !sameCluster(config.rpcUrl, SOLANA_RPC_URL)
  const me = publicKey?.toBase58()
  const awaitingProvider = chain !== null && !closed && !chain.active
  const opensAt = chain ? settleOpensAt(chain) : null
  const canSettle = chain !== null && !closed && chain.active && opensAt !== null && now >= opensAt && Boolean(publicKey)

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

  function dealAction(label: string, action: (deal: PublicKey, program: PublicKey, wallet: PublicKey) => Promise<void>) {
    if (!publicKey || !config || !address) return
    void run(label, async () => {
      await action(new PublicKey(address), new PublicKey(config.programId), publicKey)
      await wallet.refresh()
    })
  }

  const accept = () => dealAction('Locking guarantee', (deal, program, me) =>
    acceptDeal({ connection, sendTransaction, programId: program, deal, recipient: me }))
  const cancel = () => dealAction('Cancelling deal', (deal, program, me) =>
    cancelDeal({ connection, sendTransaction, programId: program, deal, payer: me }))
  const settle = () => dealAction('Settling', (deal, program, me) => chain
    ? settleDeal({ connection, sendTransaction, programId: program, deal, caller: me, payer: new PublicKey(chain.payer), recipient: new PublicKey(chain.recipient) })
    : Promise.resolve())

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!publicKey || !config || clusterMismatch) return
    const sol = Number(amountSol)
    const stake = Number(stakeSol)
    const seconds = Number(durationSeconds)
    const interval = Number(intervalSeconds)
    const percent = Number(minUptimePercent)
    if (!Number.isFinite(sol) || sol <= 0) { setError('Enter a payment in SOL.'); return }
    if (!Number.isFinite(stake) || stake < 0) { setError('Enter a provider guarantee of 0 SOL or more.'); return }
    if (!Number.isInteger(seconds) || seconds < 1 || seconds > 3600) { setError('Enter a window of 1 to 3600 seconds.'); return }
    if (totalRounds(seconds, interval) === null) { setError('The check interval must divide the window evenly.'); return }
    if (!Number.isFinite(percent) || percent < 0.01 || percent > 100) { setError('Enter a minimum uptime between 0.01% and 100%.'); return }
    void run('Creating deal', async () => {
      setAddress(null); setChain(null); setClosed(null); setMonitor(null)
      const tracked = await openDeal({
        connection, payer: publicKey, sendTransaction, config, recipient: recipient.trim(),
        amountLamports: BigInt(Math.round(sol * LAMPORTS_PER_SOL)), providerStakeLamports: BigInt(Math.round(stake * LAMPORTS_PER_SOL)),
        durationSeconds: seconds, checkIntervalSeconds: interval, minUptimeBps: Math.round(percent * BPS_DENOMINATOR / 100),
      })
      setMonitor(tracked)
      setAddress(tracked.address)
      await wallet.refresh()
    })
  }

  const settled = closed && closed !== 'unknown' && !closed.outcome.cancelled ? closed.outcome : null
  const counters = settled ? counterLine(settled.upChecks, settled.downChecks, settled.totalRounds)
    : chain ? counterLine(chain.upChecks, chain.downChecks, chain.totalRounds) : '—'
  const recipientLabel = chain?.recipient ?? monitor?.recipient

  return <div className="page-stack">
    <div className="page-heading create-heading"><div>
      <div className="eyebrow"><span className="eyebrow-line" /> ON-CHAIN SLA <span className="eyebrow-slash">/</span> MONITORS OBSERVE, SOLANA DECIDES</div>
      <h1>Uptime deal</h1>
      <p>Lock a payment for a provider, who may lock a guarantee too. The uptime service reports each round as UP or DOWN on chain; when the window ends the program compares its own counters with the threshold and pays the whole escrow to the provider, or back to you.</p>
    </div></div>

    {configError && <ErrorState message={`Uptime service unavailable: ${configError}`} retry={() => void reloadConfig()} />}
    {config && clusterMismatch && <ErrorState message={`The uptime service reports on ${config.rpcUrl}, but this app is connected to ${SOLANA_RPC_URL}. Set VITE_SOLANA_RPC_URL=${config.rpcUrl} and restart the frontend.`} />}
    {error && <ErrorState message={error} />}

    <div className="form-layout">
      <form onSubmit={submit} className="surface form-surface" noValidate aria-label="Create uptime deal">
        <div className="form-section"><div className="form-section-heading"><span className="form-step">01</span><div><h2>Your wallet</h2><p>The customer signs create_deal and funds the payment.</p></div></div>
          {publicKey ? <div className="agreement-list">
            <div><span>Payer</span><strong title={publicKey.toBase58()}>{shortAddress(publicKey.toBase58(), 6, 6)}</strong></div>
            <div><span>Wallet balance</span><strong data-testid="wallet-balance">{formatLamports(wallet.lamports)}</strong></div>
            <div><span>Need SOL on localnet or devnet?</span><button type="button" className="button" onClick={airdrop} disabled={Boolean(busy)}><Droplets size={15} /> Airdrop 2 SOL</button></div>
          </div> : <div className="agreement-list"><div><span>Connect a wallet to create a deal.</span><WalletMultiButton /></div></div>}
        </div>

        <div className="form-section"><div className="form-section-heading"><span className="form-step">02</span><div><h2>Deal terms</h2><p>All terms are stored on chain; the program settles from them.</p></div></div>
          <div className="field"><label htmlFor="recipient">Recipient address</label><input id="recipient" value={recipient} onChange={(e) => setRecipient(e.target.value)} placeholder="The provider's Solana wallet" /><small>Wins the whole escrow when the SLA is met.</small></div>
          <div className="field-grid">
            <div className="field"><label htmlFor="amount">Payment (SOL)</label><input id="amount" type="number" min="0.001" step="0.001" value={amountSol} onChange={(e) => setAmountSol(e.target.value)} /><small>At least 0.001 SOL.</small></div>
            <div className="field"><label htmlFor="stake">Provider guarantee (SOL)</label><input id="stake" type="number" min="0" step="0.001" value={stakeSol} onChange={(e) => setStakeSol(e.target.value)} /><small>0 starts at once; otherwise the provider must accept.</small></div>
          </div>
          <div className="field-grid">
            <div className="field"><label htmlFor="duration">Window (seconds)</label><input id="duration" type="number" min="1" max="3600" step="1" value={durationSeconds} onChange={(e) => setDurationSeconds(e.target.value)} /><small>1 to 3600 seconds.</small></div>
            <div className="field"><label htmlFor="interval">Check interval (seconds)</label><input id="interval" type="number" min="1" step="1" value={intervalSeconds} onChange={(e) => setIntervalSeconds(e.target.value)} /><small>One on-chain observation per round.</small></div>
            <div className="field"><label htmlFor="min-uptime">Minimum uptime (%)</label><input id="min-uptime" type="number" min="0.01" max="100" step="0.01" value={minUptimePercent} onChange={(e) => setMinUptimePercent(e.target.value)} /><small>Unobserved rounds count as down.</small></div>
          </div>
        </div>

        <div className="form-submit"><button className="button button-primary button-large" type="submit" disabled={!publicKey || !config || clusterMismatch || Boolean(busy)}>{busy ?? 'Create deal'}<ArrowRight size={17} /></button></div>
      </form>

      <aside className="summary-column">
        <section className="surface summary-card" aria-label="Uptime service">
          <div className="summary-icon"><Server size={21} /></div>
          <h2>Uptime monitor</h2>
          <p className="summary-intro">Reports each round as UP or DOWN on chain. It never decides the outcome.</p>
          <div className="summary-list">
            <div><span>Current state</span><strong data-testid="service-state">{serviceState ?? 'Unavailable'}</strong></div>
            <div><span>Oracle</span><strong title={config?.oracle}>{config ? shortAddress(config.oracle, 6, 6) : '—'}</strong></div>
            {monitor && <div><span>Observations sent</span><strong data-testid="observations-sent">{monitor.observationsSent}</strong></div>}
            {monitor?.error && <div><span>Monitor error</span><strong>{monitor.error}</strong></div>}
          </div>
          <button type="button" className="button settle-button" onClick={toggleService} disabled={!serviceState || Boolean(busy)}>{serviceState === 'DOWN' ? 'Restore service' : 'Simulate outage'}</button>
        </section>

        {address && <section className="surface summary-card" aria-label="Deal">
          <div className="summary-icon">{closed ? <ShieldCheck size={21} /> : <Timer size={21} />}</div>
          <h2>Deal (read from chain)</h2>
          <p className="summary-intro" role="status" data-testid="deal-verdict">{dealVerdict(chain, closed, now)}</p>
          <div className="summary-list">
            <div><span>Address</span><strong title={address}>{shortAddress(address, 6, 6)}</strong></div>
            {chain && <div><span>Terms</span><strong>{chain.durationSeconds}s · {chain.checkIntervalSeconds}s rounds · ≥ {formatBps(chain.minUptimeBps)}</strong></div>}
            {chain && <div><span>Oracle</span><strong title={chain.oracle} data-testid="deal-oracle">{shortAddress(chain.oracle, 6, 6)}</strong></div>}
            <div><span>On-chain counters</span><strong data-testid="deal-counters">{counters}</strong></div>
            {chain && !closed && chain.active && <div><span>Projection (informational)</span><strong data-testid="deal-projection">{projection(chain)}</strong></div>}
            {recipientLabel && <div><span>Recipient</span><strong title={recipientLabel}>{shortAddress(recipientLabel, 6, 6)}</strong></div>}
            <div><span><Coins size={16} /> Recipient balance</span><strong data-testid="recipient-balance">{formatLamports(recipientBalance.lamports)}</strong></div>
            {closed && closed !== 'unknown' && <div><span>Closing tx</span><strong title={closed.signature}>{shortAddress(closed.signature, 6, 6)}</strong></div>}
          </div>
          {awaitingProvider && me === chain?.recipient && <div className="agreement-escrow">
            <span>Lock {formatLamports(chain.providerStakeLamports)} to start the window</span>
            {chain.oracle !== config?.oracle && <span role="alert" data-testid="foreign-oracle">This deal names oracle {shortAddress(chain.oracle, 6, 6)}, not this service's monitor. Whoever holds that key reports every round.</span>}
            <button type="button" className="button settle-button" onClick={accept} disabled={Boolean(busy)}>Accept and lock guarantee</button>
          </div>}
          {awaitingProvider && me === chain?.payer && <div className="agreement-escrow">
            <span>The provider hasn't accepted yet</span>
            <button type="button" className="button settle-button" onClick={cancel} disabled={Boolean(busy)}>Cancel deal</button>
          </div>}
          {canSettle && <div className="agreement-escrow">
            <span><Gavel size={16} /> Anyone can settle; the program decides</span>
            <button type="button" className="button settle-button" onClick={settle} disabled={Boolean(busy)}>Settle now</button>
          </div>}
          {chain && <div className="agreement-escrow"><span><LockKeyhole size={16} /> Escrow</span><strong>{formatLamports(chain.amountLamports + (chain.active ? chain.providerStakeLamports : 0n))}</strong></div>}
        </section>}
      </aside>
    </div>
  </div>
}
