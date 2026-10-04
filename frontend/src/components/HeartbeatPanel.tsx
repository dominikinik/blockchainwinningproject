import { useEffect, useState } from 'react'
import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useNow } from '../hooks/useNow'
import { shortAddress } from '../lib/format'
import { heartbeatApi, type Heartbeat } from '../services/heartbeat/heartbeatApi'
import { EmptyState, ErrorState, LoadingState, ResultBadge, SectionHeading } from './UI'

const POLL_MS = 2000
const TABLE_ROWS = 12

function utcTime(iso: string) {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '—' : date.toISOString().slice(11, 19)
}

function age(iso: string, now: number) {
  const seconds = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000))
  if (Number.isNaN(seconds)) return '—'
  if (seconds < 60) return `${seconds}s ago`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  return `${Math.floor(seconds / 3600)}h ago`
}

export function HeartbeatPanel() {
  const now = useNow()
  const [beats, setBeats] = useState<Heartbeat[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const next = await heartbeatApi.getRecent(50)
        if (cancelled) return
        setBeats(next)
        setError(null)
      } catch (caught) {
        if (!cancelled) setError(caught instanceof Error ? caught.message : 'Heartbeat log unavailable.')
      }
    }
    void load()
    const interval = window.setInterval(() => void load(), POLL_MS)
    return () => { cancelled = true; window.clearInterval(interval) }
  }, [])

  const strip = beats ? [...beats].reverse() : []
  const upCount = strip.filter((beat) => beat.up).length
  const notSent = strip.filter((beat) => beat.report !== 'SENT').length
  const last = beats?.[0]

  return <section className="surface heartbeat-panel">
    <SectionHeading title="Oracle heartbeats" subtitle="Live health probes the oracle reports on chain, one per deal round." action={<span className="live-pill"><span className="live-pulse" />Live</span>} />
    {!beats && !error && <LoadingState label="Loading heartbeats" />}
    {!beats && error && <div className="heartbeat-state"><ErrorState message={error} /></div>}
    {beats && beats.length === 0 && <EmptyState title="No heartbeats yet" description="Create and accept an uptime deal; the oracle probes the provider once per round." action={<Link className="button button-primary" to="/deal">Open deals <ArrowRight size={16} /></Link>} />}
    {beats && beats.length > 0 && <>
      <div className="heartbeat-stats">
        <div><span>Beats shown</span><strong>{strip.length}</strong></div>
        <div><span>UP rate</span><strong>{Math.round((upCount / strip.length) * 100)}%</strong></div>
        <div><span>Last beat</span><strong>{last ? age(last.checkedAt, now) : '—'}</strong></div>
        <div><span>Not sent</span><strong className={notSent ? 'text-warning' : undefined}>{notSent}</strong></div>
      </div>
      {error && <p className="heartbeat-stale">Stale: could not refresh ({error}). Showing the last data.</p>}
      <div className="heartbeat-strip" data-testid="heartbeat-strip">
        {strip.map((beat) => <span key={beat.id} data-testid="heartbeat-bar" className={`heartbeat-bar ${beat.up ? 'up' : 'down'}${beat.report !== 'SENT' ? ' unsent' : ''}`} title={`${utcTime(beat.checkedAt)} UTC · ${shortAddress(beat.dealAddress)} · round ${beat.round + 1} · ${beat.outcome}`} />)}
      </div>
      <div className="table-scroll"><table className="data-table heartbeat-table"><thead><tr><th>Time</th><th>Deal</th><th>Round</th><th>Result</th><th>HTTP</th><th>Latency</th><th>Report</th></tr></thead><tbody>
        {beats.slice(0, TABLE_ROWS).map((beat) => <tr key={beat.id} data-testid="heartbeat-row">
          <td className="mono-cell">{utcTime(beat.checkedAt)}</td>
          <td><Link className="mono-cell" to={`/deal?deal=${encodeURIComponent(beat.dealAddress)}`}>{shortAddress(beat.dealAddress)}</Link></td>
          <td>#{beat.round + 1}</td>
          <td><ResultBadge result={beat.up ? 'up' : 'down'} /></td>
          <td>{beat.httpStatus ?? '—'}</td>
          <td>{beat.latencyMs} ms</td>
          <td><span className={`report-badge report-${beat.report.toLowerCase()}`} title={beat.reportError ?? undefined}>{beat.report}</span></td>
        </tr>)}
      </tbody></table></div>
    </>}
  </section>
}
