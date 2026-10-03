import { Activity, ArrowDownRight, ArrowRight, ArrowUpRight, Clock3, Coins, Plus, ShieldCheck, TriangleAlert } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { EmptyState, ErrorState, LoadingState, SectionHeading, StatusBadge } from '../components/UI'
import { useAsyncData } from '../hooks/useAsyncData'
import { useNow } from '../hooks/useNow'
import { formatSol, shortAddress, timeRemainingPrecise, totalEscrowSol } from '../lib/format'
import { slaService } from '../services/solana/slaService'

export function DashboardPage() {
  const navigate = useNavigate()
  const now = useNow()
  const { data: slas, loading, error, reload } = useAsyncData(() => slaService.getSLAs(), [])
  const active = slas?.filter((sla) => sla.settlement.state !== 'settled' && new Date(sla.endAt).getTime() > now) ?? []
  const measured = slas?.filter((sla) => sla.successfulChecks + sla.failedChecks > 0) ?? []
  const averageUptime = measured.length ? measured.reduce((sum, sla) => sum + sla.currentUptime, 0) / measured.length : 0
  const unsettled = slas?.filter((sla) => sla.settlement.state !== 'settled') ?? []
  const locked = unsettled.reduce((sum, sla) => sum + totalEscrowSol(sla.customerPaymentSol, sla.providerGuaranteeSol), 0)
  const violations = slas?.filter((sla) => sla.status === 'violated').length ?? 0

  return <div className="page-stack">
    <div className="page-heading dashboard-heading">
      <div><div className="eyebrow"><span className="eyebrow-line" /> OVERVIEW <span className="eyebrow-slash">/</span> DEVNET</div><h1>Service agreements,<br /> <span>clearly accounted for.</span></h1><p>Monitor performance and follow every SOL from escrow to settlement.</p></div>
      <Link className="button button-primary heading-action" to="/create"><Plus size={17} /> Create SLA</Link>
    </div>

    {error && <ErrorState message={error} retry={() => void reload()} />}
    {loading && <LoadingState label="Loading agreements" />}
    {!loading && slas && <>
      <div className="metrics-grid dashboard-metrics">
        <div className="metric-card"><div className="metric-top"><span>Active SLAs</span><span className="metric-icon icon-indigo"><Activity size={19} /></span></div><strong>{active.length.toString().padStart(2, '0')}</strong><div className="metric-foot"><span className="metric-foot-positive"><ArrowUpRight size={14} /> Live</span> agreements in progress</div></div>
        <div className="metric-card"><div className="metric-top"><span>SOL Locked</span><span className="metric-icon icon-violet"><Coins size={19} /></span></div><strong>{formatSol(locked)}</strong><div className="metric-foot">Customer payments + provider guarantees</div></div>
        <div className="metric-card"><div className="metric-top"><span>Average Uptime</span><span className="metric-icon icon-teal"><ShieldCheck size={19} /></span></div><strong>{averageUptime.toFixed(2)}%</strong><div className="metric-foot"><span className="metric-foot-positive"><ArrowUpRight size={14} /> Observed</span> across monitored APIs</div></div>
        <div className="metric-card"><div className="metric-top"><span>SLA Violations</span><span className="metric-icon icon-amber"><TriangleAlert size={19} /></span></div><strong>{violations.toString().padStart(2, '0')}</strong><div className="metric-foot"><span className="metric-foot-warning"><ArrowDownRight size={14} /> Action needed</span> on ended agreements</div></div>
      </div>

      <section className="surface table-surface">
        <SectionHeading title="Your agreements" subtitle="A live view of your API service commitments." action={<span className="subtle-count">{slas.length} total agreements</span>} />
        {slas.length === 0 ? <EmptyState title="No agreements yet" description="Create an SLA to start tracking an API and its escrow." action={<Link className="button button-primary" to="/create">Create SLA <ArrowRight size={16} /></Link>} /> : <div className="table-scroll"><table className="data-table sla-table"><thead><tr><th>API / SLA</th><th>Provider</th><th>Required Uptime</th><th>Current Uptime</th><th>Total Escrow</th><th>Time Remaining</th><th>Status</th><th aria-label="Open" /></tr></thead><tbody>{slas.map((sla) => <tr key={sla.id} onClick={() => navigate(`/sla/${sla.id}`)} className="clickable-row"><td><Link to={`/sla/${sla.id}`} onClick={(event) => event.stopPropagation()} className="table-primary-link">{sla.name}</Link><span className="cell-subtext">{sla.endpoint}</span></td><td className="mono-cell">{shortAddress(sla.providerWallet)}</td><td>{sla.requiredUptime}%</td><td><span className={sla.status === 'violated' ? 'text-danger' : sla.status === 'at-risk' ? 'text-warning' : 'text-success'}>{sla.successfulChecks + sla.failedChecks ? `${sla.currentUptime.toFixed(2)}%` : '—'}</span></td><td className="font-semibold">{formatSol(totalEscrowSol(sla.customerPaymentSol, sla.providerGuaranteeSol))}</td><td><span className="time-cell"><Clock3 size={14} />{timeRemainingPrecise(sla.endAt, now)}</span></td><td><StatusBadge status={sla.status} /></td><td className="row-chevron"><ArrowUpRight size={16} /></td></tr>)}</tbody></table></div>}
      </section>

      <div className="dashboard-bottom">
        <div className="info-panel dark-panel"><span className="panel-kicker">MONITORING</span><h3>Check service health,<br />second by second.</h3><p>Review the Java uptime service's recent state and per-second history.</p><Link to="/monitoring">View uptime timeline <ArrowRight size={16} /></Link><div className="dark-panel-lines" aria-hidden="true"><span /><span /><span /></div></div>
        <div className="info-panel light-panel"><div className="light-panel-icon"><ShieldCheck size={22} /></div><h3>Settlement belongs<br />to the program.</h3><p>Dashboard indicators and projections are for visibility. The on-chain program is the source of truth for settlement.</p><div className="light-panel-foot"><span className="mini-dot" /> Solana Devnet</div></div>
      </div>
    </>}
  </div>
}
