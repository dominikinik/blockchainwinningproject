import { Activity, ArrowDown, ArrowRight, CheckCircle2, CircleDot, Database, FileCheck2, Globe2, Radio, ShieldCheck } from 'lucide-react'
import { EmptyState, ErrorState, LoadingState, SectionHeading } from '../components/UI'
import { useAsyncData } from '../hooks/useAsyncData'
import { shortAddress, timeAgo } from '../lib/format'
import { slaService } from '../services/solana/slaService'

const steps = [
  { icon: Globe2, title: 'Your API', detail: 'Endpoint health' },
  { icon: Radio, title: 'Independent monitors', detail: 'Distributed checks' },
  { icon: FileCheck2, title: 'Signed observations', detail: 'Verifiable reports' },
  { icon: Database, title: 'Solana program', detail: 'On-chain record' },
  { icon: ShieldCheck, title: 'Consensus + calculation', detail: 'Program decision' },
  { icon: CheckCircle2, title: 'Settlement', detail: 'Funds distributed' },
]

export function MonitoringPage() {
  const { data: monitors, loading, error, reload } = useAsyncData(() => slaService.getMonitors(), [])
  const online = monitors?.filter((monitor) => monitor.status === 'online').length ?? 0
  const totalObservations = monitors?.reduce((sum, monitor) => sum + monitor.observations, 0) ?? 0
  const agreementRate = monitors?.length ? monitors.reduce((sum, monitor) => sum + monitor.agreementRate, 0) / monitors.length : 0
  return <div className="page-stack">
    <div className="page-heading monitoring-heading"><div><div className="eyebrow"><span className="eyebrow-line" /> NETWORK <span className="eyebrow-slash">/</span> MONITORING</div><h1>Independent by design.</h1><p>Distributed observers provide the signed evidence behind every agreement.</p></div><div className="monitoring-live"><span className="pulse-dot" /> Network operational</div></div>
    {error && <ErrorState message={error} retry={() => void reload()} />}
    {loading && <LoadingState label="Loading monitors" />}
    {!loading && monitors && <>
      <div className="monitoring-stats"><div><span className="monitoring-stat-icon"><Radio size={18} /></span><strong>{String(online).padStart(2, '0')} / {String(monitors.length).padStart(2, '0')}</strong><span>Monitors online</span></div><div><span className="monitoring-stat-icon"><Activity size={18} /></span><strong>{totalObservations.toLocaleString()}</strong><span>Total observations</span></div><div><span className="monitoring-stat-icon"><ShieldCheck size={18} /></span><strong>{agreementRate.toFixed(1)}%</strong><span>Average agreement rate</span></div></div>
      <section className="surface table-surface"><SectionHeading title="Registered monitors" subtitle="Independent nodes contributing observations to the network." action={<span className="subtle-count"><CircleDot size={13} /> {online} online</span>} />
        {monitors.length ? <div className="table-scroll"><table className="data-table monitors-table"><thead><tr><th>Monitor</th><th>Wallet</th><th>Status</th><th>Observations</th><th>Agreement Rate</th><th>Last Observation</th></tr></thead><tbody>{monitors.map((monitor) => <tr key={monitor.id}><td><span className="monitor-name"><span className="monitor-avatar">{monitor.name.slice(-1)}</span><strong>{monitor.name}</strong></span></td><td className="mono-cell">{shortAddress(monitor.wallet, 4, 4)}</td><td><span className={`monitor-status ${monitor.status}`}><span className="status-dot" />{monitor.status === 'online' ? 'Online' : 'Offline'}</span></td><td>{monitor.observations.toLocaleString()}</td><td><span className="agreement-rate"><span style={{ width: `${monitor.agreementRate}%` }} /></span><strong>{monitor.agreementRate.toFixed(1)}%</strong></td><td>{timeAgo(monitor.lastObservationAt)}</td></tr>)}</tbody></table></div> : <EmptyState title="No monitors registered" description="Registered nodes will appear here when available." />}
      </section>
      <section className="surface flow-section"><SectionHeading title="How monitoring works" subtitle="A verifiable path from an API response to a settlement decision." /><div className="flow-steps">{steps.map(({ icon: Icon, title, detail }, index) => <div className="flow-item-wrap" key={title}><div className="flow-item"><span className="flow-number">0{index + 1}</span><span className="flow-icon"><Icon size={21} /></span><strong>{title}</strong><small>{detail}</small></div>{index < steps.length - 1 && <span className="flow-arrow"><ArrowRight className="desktop-flow-arrow" size={18} /><ArrowDown className="mobile-flow-arrow" size={18} /></span>}</div>)}</div><div className="flow-note"><span><ShieldCheck size={17} /></span><p>Monitor reports are evidence. The Solana program will calculate consensus, evaluate the SLA, and authorize settlement.</p></div></section>
      <div className="monitoring-footer-callout"><div><span className="callout-kicker">BUILT FOR VERIFIABILITY</span><h3>Every observation leaves a trail.</h3><p>Signed submissions will be inspectable on Solana Explorer once on-chain integration is connected.</p></div><span className="callout-mark"><ArrowRight size={25} /></span></div>
    </>}
  </div>
}
