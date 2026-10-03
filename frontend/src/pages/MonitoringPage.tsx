import { useEffect } from 'react'
import { Activity, Clock3, Radio, RefreshCw, Server, TriangleAlert } from 'lucide-react'
import { ErrorState, LoadingState, SectionHeading } from '../components/UI'
import { UptimeTimeline } from '../components/UptimeTimeline'
import { useAsyncData } from '../hooks/useAsyncData'
import { uptimeService } from '../services/uptime/uptimeService'

export function MonitoringPage() {
  const { data: backendState, loading: stateLoading, error: stateError, reload: reloadState } = useAsyncData(() => uptimeService.getState(), [])
  const { data: history, loading: historyLoading, error: historyError, reload: reloadHistory } = useAsyncData(() => uptimeService.getHistory(), [])

  useEffect(() => {
    const interval = window.setInterval(() => {
      void reloadState()
      void reloadHistory()
    }, 10_000)
    return () => window.clearInterval(interval)
  }, [reloadState, reloadHistory])

  const refresh = () => {
    void reloadState()
    void reloadHistory()
  }
  const visibleHistory = historyError ? null : history
  const upSeconds = visibleHistory?.filter((point) => !point.down).length ?? 0
  const downSeconds = (visibleHistory?.length ?? 0) - upSeconds
  const uptime = visibleHistory?.length ? `${(upSeconds / visibleHistory.length * 100).toFixed(1)}%` : '—'
  const statusLabel = stateError ? 'Backend unavailable' : backendState ? `Uptime service ${backendState}` : 'Checking backend'

  return <div className="page-stack">
    <div className="page-heading monitoring-heading">
      <div>
        <div className="eyebrow"><span className="eyebrow-line" /> LIVE SERVICE <span className="eyebrow-slash">/</span> UPTIME</div>
        <h1>Uptime, second by second.</h1>
        <p>A live view of the Java uptime service over the last five minutes.</p>
      </div>
      <div className={`monitoring-live ${stateError ? 'is-unavailable' : backendState === 'DOWN' ? 'is-down' : ''}`}><span className="pulse-dot" /> {statusLabel}</div>
    </div>

    <section className="surface backend-state-panel">
      <span className="backend-state-icon"><Server size={19} /></span>
      <div>
        <span className="backend-state-kicker">LIVE BACKEND CONNECTION</span>
        <h2>Uptime service</h2>
        <p>{stateError ? 'The service is unavailable. Start the Java service and refresh.' : backendState === 'DOWN' ? 'The service reports DOWN. The timeline below shows its recorded history.' : backendState === 'UP' ? 'The service reports UP. The timeline below shows its recorded history.' : 'Checking the service state...'}</p>
      </div>
      <button type="button" onClick={refresh} disabled={stateLoading || historyLoading} aria-label="Refresh uptime data"><RefreshCw size={16} /></button>
    </section>

    <div className="monitoring-stats">
      <div><span className="monitoring-stat-icon"><Radio size={18} /></span><strong>{stateError ? 'Unavailable' : backendState ?? '—'}</strong><span>Current state</span></div>
      <div><span className="monitoring-stat-icon"><Activity size={18} /></span><strong>{uptime}</strong><span>Uptime · 5 min</span></div>
      <div><span className="monitoring-stat-icon"><TriangleAlert size={18} /></span><strong>{visibleHistory ? downSeconds.toLocaleString() : '—'}</strong><span>Down seconds</span></div>
    </div>

    <section className="surface live-history-panel">
      <SectionHeading title="Uptime timeline" subtitle="Green means UP; red means at least one DOWN second in that block." action={<span className="legend"><span><i className="legend-up" /> Up</span><span><i className="legend-down" /> Down</span></span>} />
      {historyError ? <div className="live-history-state"><ErrorState message="Could not load uptime history from the backend." retry={refresh} /></div> : historyLoading && !history ? <div className="live-history-state"><LoadingState label="Loading uptime history" /></div> : visibleHistory ? <UptimeTimeline points={visibleHistory} /> : null}
      <div className="live-history-footer"><Clock3 size={15} /> Recorded per-second history · refreshes every 10 seconds</div>
    </section>

    <div className="service-scope-note"><Server size={17} /><p>This timeline shows the Java uptime service's own recorded state.</p></div>
  </div>
}
