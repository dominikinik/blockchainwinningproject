import { useState, type FormEvent } from 'react'
import { ArrowLeft, ArrowRight, CheckCircle2, CircleHelp, Clock3, Coins, Info, LockKeyhole, Radio, ShieldCheck } from 'lucide-react'
import { useNavigate, Link } from 'react-router-dom'
import { useWallet } from '@solana/wallet-adapter-react'
import { useWalletModal } from '@solana/wallet-adapter-react-ui'
import { PublicKey } from '@solana/web3.js'
import { ErrorState } from '../components/UI'
import { formatSol } from '../lib/format'
import { slaService } from '../services/solana/slaService'
import type { CreateSLAInput } from '../types'

type Draft = Omit<CreateSLAInput, 'customerWallet' | 'monitorCount'>
const initialDraft: Draft = {
  name: '', endpoint: '', providerWallet: '', escrowSol: 10, requiredUptime: 99.9,
  durationDays: 7, checkIntervalMinutes: 5, timeoutMs: 2000, consensusRequired: 3,
}
const MONITOR_COUNT = 5

function validate(draft: Draft): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!draft.name.trim()) errors.name = 'Give this agreement a name.'
  try {
    const url = new URL(draft.endpoint)
    if (url.protocol !== 'https:') errors.endpoint = 'Use an HTTPS endpoint.'
  } catch { errors.endpoint = 'Enter a valid HTTPS URL.' }
  try { new PublicKey(draft.providerWallet) } catch { errors.providerWallet = 'Enter a valid Solana wallet address.' }
  if (!Number.isFinite(draft.escrowSol) || draft.escrowSol <= 0 || draft.escrowSol > 10000) errors.escrowSol = 'Enter an amount between 0 and 10,000 SOL.'
  if (!Number.isFinite(draft.requiredUptime) || draft.requiredUptime <= 0 || draft.requiredUptime > 100) errors.requiredUptime = 'Enter a percentage above 0 and up to 100.'
  if (!Number.isInteger(draft.timeoutMs) || draft.timeoutMs < 100 || draft.timeoutMs > 30000) errors.timeoutMs = 'Enter a timeout between 100 and 30,000 ms.'
  return errors
}

export function CreateSLAPage() {
  const navigate = useNavigate()
  const { publicKey } = useWallet()
  const { setVisible } = useWalletModal()
  const [draft, setDraft] = useState<Draft>(initialDraft)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  function update<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((current) => ({ ...current, [key]: value }))
    setErrors((current) => ({ ...current, [key]: '' }))
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextErrors = validate(draft)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length) return
    if (!publicKey) { setVisible(true); return }
    setSubmitting(true)
    setSubmitError(null)
    try {
      const created = await slaService.createSLA({ ...draft, name: draft.name.trim(), endpoint: draft.endpoint.trim(), providerWallet: draft.providerWallet.trim(), customerWallet: publicKey.toBase58(), monitorCount: MONITOR_COUNT })
      navigate(`/sla/${created.id}`)
    } catch (cause) {
      setSubmitError(cause instanceof Error ? cause.message : 'Could not create the agreement.')
    } finally { setSubmitting(false) }
  }

  return <div className="page-stack">
    <div className="page-heading create-heading"><div><Link to="/" className="back-link"><ArrowLeft size={15} /> Back to dashboard</Link><div className="eyebrow"><span className="eyebrow-line" /> NEW AGREEMENT</div><h1>Create an SLA</h1><p>Define your service standard and the terms your monitors will verify.</p></div></div>
    <div className="form-layout">
      <form id="create-sla-form" onSubmit={handleSubmit} className="surface form-surface" noValidate>
        <div className="form-section"><div className="form-section-heading"><span className="form-step">01</span><div><h2>Service details</h2><p>What API are you holding accountable?</p></div></div>
          <div className="field-grid"><div className="field"><label htmlFor="name">Agreement name</label><input id="name" value={draft.name} onChange={(e) => update('name', e.target.value)} placeholder="e.g. Payments API" aria-invalid={Boolean(errors.name)} /><small>{errors.name || 'A name to identify this agreement.'}</small></div><div className="field"><label htmlFor="endpoint">API endpoint</label><input id="endpoint" type="url" value={draft.endpoint} onChange={(e) => update('endpoint', e.target.value)} placeholder="https://api.example.com/health" aria-invalid={Boolean(errors.endpoint)} /><small>{errors.endpoint || 'The HTTPS endpoint monitors will check.'}</small></div></div>
          <div className="field"><label htmlFor="provider">Provider wallet address</label><input id="provider" value={draft.providerWallet} onChange={(e) => update('providerWallet', e.target.value)} placeholder="Enter a Solana wallet address" aria-invalid={Boolean(errors.providerWallet)} /><small>{errors.providerWallet || 'The provider who receives funds if the agreement is met.'}</small></div>
        </div>
        <div className="form-section"><div className="form-section-heading"><span className="form-step">02</span><div><h2>Agreement terms</h2><p>Set the funds and the performance threshold.</p></div></div>
          <div className="field-grid"><div className="field"><label htmlFor="escrow">Escrow amount</label><div className="input-with-suffix"><input id="escrow" type="number" min="0.001" max="10000" step="0.001" value={draft.escrowSol} onChange={(e) => update('escrowSol', Number(e.target.value))} aria-invalid={Boolean(errors.escrowSol)} /><span>SOL</span></div><small>{errors.escrowSol || 'Locked by the customer for this agreement.'}</small></div><div className="field"><label htmlFor="uptime">Required uptime</label><div className="input-with-suffix"><input id="uptime" type="number" min="0.01" max="100" step="0.01" value={draft.requiredUptime} onChange={(e) => update('requiredUptime', Number(e.target.value))} aria-invalid={Boolean(errors.requiredUptime)} /><span>%</span></div><small>{errors.requiredUptime || 'Minimum acceptable uptime over the period.'}</small></div></div>
          <div className="field-grid"><div className="field"><label htmlFor="duration">SLA duration</label><select id="duration" value={draft.durationDays} onChange={(e) => update('durationDays', Number(e.target.value))}><option value={1}>1 day</option><option value={7}>7 days</option><option value={14}>14 days</option><option value={30}>30 days</option></select><small>Starts when the agreement is created.</small></div><div className="field"><label htmlFor="interval">Check interval</label><select id="interval" value={draft.checkIntervalMinutes} onChange={(e) => update('checkIntervalMinutes', Number(e.target.value))}><option value={1}>Every 1 minute</option><option value={2}>Every 2 minutes</option><option value={5}>Every 5 minutes</option><option value={10}>Every 10 minutes</option></select><small>How often each monitor checks the API.</small></div></div>
          <div className="field-grid"><div className="field"><label htmlFor="timeout">Request timeout</label><div className="input-with-suffix"><input id="timeout" type="number" min="100" max="30000" step="100" value={draft.timeoutMs} onChange={(e) => update('timeoutMs', Number(e.target.value))} aria-invalid={Boolean(errors.timeoutMs)} /><span>ms</span></div><small>{errors.timeoutMs || 'A slower response counts as a failed check.'}</small></div><div className="field"><label htmlFor="consensus">Required monitor consensus</label><select id="consensus" value={draft.consensusRequired} onChange={(e) => update('consensusRequired', Number(e.target.value))}>{[3, 4, 5].map((count) => <option key={count} value={count}>{count} of {MONITOR_COUNT} monitors</option>)}</select><small>Minimum agreeing signed observations.</small></div></div>
        </div>
        {submitError && <div className="form-submit-error"><ErrorState message={submitError} /></div>}
        <div className="form-submit"><div className="mock-note"><Info size={16} /><span>Demo mode: this creates a local mock SLA. No SOL is transferred.</span></div><button className="button button-primary button-large" type="submit" disabled={submitting}>{submitting ? 'Creating agreement...' : 'Create demo SLA'}<ArrowRight size={17} /></button></div>
      </form>

      <aside className="summary-column"><div className="surface summary-card"><div className="summary-icon"><ShieldCheck size={21} /></div><h2>Agreement summary</h2><p className="summary-intro">A clear view of the terms before you create.</p><div className="summary-divider" /><div className="summary-list"><div><span><LockKeyhole size={16} /> Customer locks</span><strong>{formatSol(draft.escrowSol || 0)}</strong></div><div><span><Coins size={16} /> Provider receives</span><strong>{formatSol(draft.escrowSol || 0)}</strong></div><div><span><CircleHelp size={16} /> If below {draft.requiredUptime || 0}%</span><strong>Customer refunded</strong></div><div><span><Clock3 size={16} /> Duration</span><strong>{draft.durationDays} {draft.durationDays === 1 ? 'day' : 'days'}</strong></div><div><span><Radio size={16} /> Monitoring</span><strong>{MONITOR_COUNT} independent nodes</strong></div><div><span><CheckCircle2 size={16} /> Consensus</span><strong>{draft.consensusRequired} of {MONITOR_COUNT}</strong></div></div><div className="summary-rule"><span>SETTLEMENT RULE</span><p>Provider receives the escrow if measured uptime is at least {draft.requiredUptime || 0}%. Otherwise, the customer is refunded.</p></div></div><div className="summary-help"><Info size={17} /><p>Final settlement will be determined by the Solana program. This summary is a preview of the proposed terms.</p></div></aside>
    </div>
  </div>
}
