import { useState } from 'react'
import { Activity, ArrowLeft, ArrowRight, CheckCircle2, Clock3, Coins, ExternalLink, Info, LockKeyhole, Radio, ShieldCheck } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { useWallet } from '@solana/wallet-adapter-react'
import { useWalletModal } from '@solana/wallet-adapter-react-ui'
import { EmptyState, ErrorState, LoadingState, ResultBadge, SectionHeading, StatusBadge } from '../components/UI'
import { useAsyncData } from '../hooks/useAsyncData'
import { formatDate, formatSol, shortAddress, timeRemaining } from '../lib/format'
import { slaService } from '../services/solana/slaService'

export function SLADetailsPage() {
  const { id } = useParams()
  const { publicKey } = useWallet()
  const { setVisible } = useWalletModal()
  const { data, loading, error, reload } = useAsyncData(async () => {
    const [sla, observations, consensus, monitors] = await Promise.all([
      slaService.getSLA(id || ''), slaService.getObservations(id || ''),
      slaService.getConsensus(id || ''), slaService.getMonitors(),
    ])
    return { sla, observations, consensus, monitors }
  }, [id])
  const [settling, setSettling] = useState(false)
  const [settleError, setSettleError] = useState<string | null>(null)

  async function handleSettle() {
    if (!id) return
    if (!publicKey) { setVisible(true); return }
    setSettling(true)
    setSettleError(null)
    try { await slaService.settleSLA(id); await reload() }
    catch (cause) { setSettleError(cause instanceof Error ? cause.message : 'Could not settle SLA.') }
    finally { setSettling(false) }
  }

  if (loading && !data) return <div className="page-stack"><LoadingState label="Loading agreement" /></div>
  if (error && !data) return <div className="page-stack"><ErrorState message={error} retry={() => void reload()} /></div>
  if (!data?.sla) return <div className="page-stack"><EmptyState title="Agreement not found" description="The SLA may have been removed or the link is incorrect." action={<Link className="button button-primary" to="/">Back to dashboard <ArrowRight size={16} /></Link>} /></div>

  const { sla, observations, consensus, monitors } = data
  const totalChecks = sla.successfulChecks + sla.failedChecks
  const canSettle = sla.settlement.state === 'ready'
  return <div className="page-stack details-page">
    <div className="page-heading details-heading"><div><Link to="/" className="back-link"><ArrowLeft size={15} /> All agreements</Link><div className="details-title-row"><div><div className="eyebrow"><span className="eyebrow-line" /> AGREEMENT DETAILS <span className="eyebrow-slash">/</span> {sla.id.slice(0, 16).toUpperCase()}</div><h1>{sla.name}</h1><p className="endpoint-line"><Activity size={17} />{sla.endpoint}<ExternalLink size={14} /></p></div><StatusBadge status={sla.status} /></div></div></div>

    <div className="metrics-grid details-metrics">
      <div className="metric-card small"><span>Required uptime</span><strong>{sla.requiredUptime}%</strong><small>Agreement threshold</small></div>
      <div className="metric-card small"><span>Current uptime</span><strong className={sla.status === 'violated' ? 'text-danger' : sla.status === 'at-risk' ? 'text-warning' : 'text-success'}>{totalChecks ? `${sla.currentUptime.toFixed(2)}%` : '—'}</strong><small>{totalChecks ? 'Measured so far' : 'Awaiting observations'}</small></div>
      <div className="metric-card small"><span>Successful checks</span><strong>{sla.successfulChecks.toLocaleString()}</strong><small>Signed UP results</small></div>
      <div className="metric-card small"><span>Failed checks</span><strong>{sla.failedChecks.toLocaleString()}</strong><small>Signed DOWN results</small></div>
      <div className="metric-card small"><span>Escrow</span><strong>{formatSol(sla.escrowSol)}</strong><small>Mock funds in agreement</small></div>
      <div className="metric-card small"><span>Time remaining</span><strong>{timeRemaining(sla.endAt)}</strong><small>Until SLA end time</small></div>
    </div>

    <div className="details-grid">
      <div className="details-main">
        <section className="surface details-section"><SectionHeading title="Uptime timeline" subtitle="Recent signed UP and DOWN observations across the agreement." action={<span className="legend"><span><i className="legend-up" /> Up</span><span><i className="legend-down" /> Down</span></span>} />
          {sla.timeline.length ? <><div className="timeline-grid" aria-label="Recent observation timeline">{sla.timeline.map((result, index) => <span key={index} className={`timeline-block ${result}`} title={`Observation ${index + 1}: ${result.toUpperCase()}`} />)}</div><div className="timeline-axis"><span>7 days ago</span><span>5 days ago</span><span>3 days ago</span><span>Yesterday</span><span>Now</span></div><div className="timeline-summary"><span className="timeline-summary-icon"><Activity size={17} /></span><div><strong>{totalChecks.toLocaleString()} checks recorded</strong><span>{sla.successfulChecks.toLocaleString()} successful · {sla.failedChecks.toLocaleString()} failed</span></div><span className="timeline-rate">{sla.currentUptime.toFixed(2)}% uptime</span></div></> : <EmptyState title="No observations yet" description="Monitor results will appear here once checks begin." />}
        </section>

        <section className="surface details-section"><SectionHeading title="Current monitor consensus" subtitle="Latest independent readings for this API." action={consensus && <span className="consensus-count">{consensus.upCount} / {consensus.totalCount} <ArrowRight size={15} /> <ResultBadge result={consensus.result} /></span>} />
          {consensus ? <div className="monitor-readings">{consensus.readings.map((reading) => { const monitor = monitors.find((item) => item.id === reading.monitorId); return <div className="monitor-reading" key={reading.monitorId}><span className="monitor-avatar">{monitor?.name.slice(-1) || '?'}</span><strong>{monitor?.name || reading.monitorId}</strong><span className="reading-bar" /><ResultBadge result={reading.result} /><span className="reading-latency">{reading.latencyMs === null ? 'Timeout' : `${reading.latencyMs}ms`}</span></div> })}</div> : <EmptyState title="Awaiting consensus" description="Monitor readings will appear after the first check." />}
        </section>

        <section className="surface table-surface observations-section"><SectionHeading title="Recent observations" subtitle="Signed reports submitted by independent monitors." action={<span className="subtle-count">Latest {observations.length}</span>} />
          {observations.length ? <div className="table-scroll"><table className="data-table"><thead><tr><th>Timestamp</th><th>Monitor</th><th>Result</th><th>Latency</th><th>Transaction</th></tr></thead><tbody>{observations.map((observation) => <tr key={observation.id}><td>{formatDate(observation.timestamp)}</td><td>{monitors.find((monitor) => monitor.id === observation.monitorId)?.name || observation.monitorId}</td><td><ResultBadge result={observation.result} /></td><td>{observation.latencyMs === null ? 'Timeout' : `${observation.latencyMs}ms`}</td><td><span className="tx-mock" title="Mock signature. No Devnet transaction exists yet.">{shortAddress(observation.transaction, 6, 5)} <ExternalLink size={13} /></span></td></tr>)}</tbody></table></div> : <EmptyState title="No observations yet" description="Signed monitor reports will be listed here." />}
        </section>
      </div>

      <aside className="details-aside">
        <section className="surface agreement-card"><div className="aside-title"><span className="aside-icon"><ShieldCheck size={19} /></span><h2>Agreement</h2></div><div className="agreement-list"><div><span>Customer wallet</span><strong title={sla.customerWallet}>{shortAddress(sla.customerWallet, 6, 5)}</strong></div><div><span>Provider wallet</span><strong title={sla.providerWallet}>{shortAddress(sla.providerWallet, 6, 5)}</strong></div><div><span>Start time</span><strong>{formatDate(sla.startAt)}</strong></div><div><span>End time</span><strong>{formatDate(sla.endAt)}</strong></div><div><span>Required uptime</span><strong>{sla.requiredUptime}%</strong></div><div><span>Check interval</span><strong>Every {sla.checkIntervalMinutes} min</strong></div><div><span>Request timeout</span><strong>{sla.timeoutMs.toLocaleString()} ms</strong></div><div><span>Consensus</span><strong>{sla.consensusRequired} of {sla.monitorCount} monitors</strong></div></div><div className="agreement-escrow"><span><LockKeyhole size={16} /> Escrow amount</span><strong>{formatSol(sla.escrowSol)}</strong></div></section>

        <section className="surface settlement-card"><div className="aside-title"><span className="aside-icon purple"><Coins size={19} /></span><h2>Settlement</h2></div>
          {sla.settlement.state === 'settled' ? <div className="settled-result"><span className="settled-icon"><CheckCircle2 size={22} /></span><strong>Mock settlement complete</strong><p>{formatSol(sla.escrowSol)} assigned to the {sla.settlement.actualRecipient}. No on-chain transfer occurred.</p><small>Mock transaction: {sla.settlement.transaction && shortAddress(sla.settlement.transaction, 6, 5)}</small></div> : <><div className="projection-label">DISPLAY PROJECTION</div>{sla.settlement.projectionRecipient ? <><div className="projection-equation"><span>{sla.currentUptime.toFixed(2)}%<small>Current uptime</small></span><span className="projection-vs">vs</span><span>{sla.requiredUptime}%<small>Requirement</small></span></div><div className="projection-result"><span>If the SLA ended now</span><strong>{formatSol(sla.settlement.projectionAmountSol ?? sla.escrowSol)} <ArrowRight size={19} /> {sla.settlement.projectionRecipient === 'provider' ? 'Provider' : 'Customer'}</strong></div></> : <p className="projection-empty">A projection will appear after observations are available.</p>}<div className="projection-disclaimer"><Info size={16} /><p>This is a display projection only. The Solana program will determine the actual settlement result.</p></div>{canSettle && <button className="button button-primary settle-button" onClick={() => void handleSettle()} disabled={settling}>{settling ? 'Settling...' : 'Settle SLA'} <ArrowRight size={16} /></button>}{!canSettle && <div className="settlement-wait"><Clock3 size={15} /> Settlement available after the end time</div>}{settleError && <div className="settle-error"><ErrorState message={settleError} /></div>}</>}
        </section>
        <div className="aside-footnote"><Radio size={16} /> Monitoring and transactions shown here are mock data.</div>
      </aside>
    </div>
  </div>
}
