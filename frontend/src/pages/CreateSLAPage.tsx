import { useState, type FormEvent } from 'react'
import { ArrowLeft, ArrowRight, Clock3, Coins, LockKeyhole, ShieldCheck } from 'lucide-react'
import { useNavigate, Link } from 'react-router-dom'
import { useWallet } from '@solana/wallet-adapter-react'
import { useWalletModal } from '@solana/wallet-adapter-react-ui'
import { PublicKey } from '@solana/web3.js'
import { ErrorState } from '../components/UI'
import { durationOptions, formatDuration } from '../lib/agreementTerms'
import { formatSol, totalEscrowSol } from '../lib/format'
import { slaService } from '../services/solana/slaService'
import type { CreateSLAInput } from '../types'

type Draft = Omit<CreateSLAInput, 'customerWallet' | 'monitorCount' | 'customerPaymentSol' | 'providerGuaranteeSol'> & {
  customerPaymentSol: string
  providerGuaranteeSol: string
}
const initialDraft: Draft = {
  name: '', endpoint: '', providerWallet: '', customerPaymentSol: '10', providerGuaranteeSol: '2', requiredUptime: 99.9,
  durationDays: durationOptions[1].value, consensusRequired: 1,
}
const MONITOR_COUNT = 1

function validate(draft: Draft): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!draft.name.trim()) errors.name = 'Give this agreement a name.'
  try {
    const url = new URL(draft.endpoint)
    if (url.protocol !== 'https:') errors.endpoint = 'Use an HTTPS endpoint.'
  } catch { errors.endpoint = 'Enter a valid HTTPS URL.' }
  try { new PublicKey(draft.providerWallet) } catch { errors.providerWallet = 'Enter a valid Solana wallet address.' }
  for (const [field, value, label] of [
    ['customerPaymentSol', draft.customerPaymentSol, 'Customer payment'],
    ['providerGuaranteeSol', draft.providerGuaranteeSol, 'Provider guarantee'],
  ] as const) {
    const amount = Number(value)
    if (!Number.isFinite(amount) || amount < 0.000000001 || amount > 10_000 || !/^(?:\d+|\d*\.\d{1,9})$/.test(value)) {
      errors[field] = `${label} must be between 0.000000001 and 10,000 SOL, with up to 9 decimals.`
    }
  }
  if (!Number.isFinite(draft.requiredUptime) || draft.requiredUptime <= 0 || draft.requiredUptime > 100) errors.requiredUptime = 'Enter a percentage above 0 and up to 100.'
  if (!durationOptions.some((option) => option.value === draft.durationDays)) errors.durationDays = 'Choose a duration.'
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
  const customerPayment = Number(draft.customerPaymentSol)
  const providerGuarantee = Number(draft.providerGuaranteeSol)
  const totalEscrow = draft.customerPaymentSol.trim() && draft.providerGuaranteeSol.trim() && Number.isFinite(customerPayment + providerGuarantee)
    ? formatSol(totalEscrowSol(customerPayment, providerGuarantee)) : '—'

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
      const created = await slaService.createSLA({ ...draft, customerPaymentSol: Number(draft.customerPaymentSol), providerGuaranteeSol: Number(draft.providerGuaranteeSol), name: draft.name.trim(), endpoint: draft.endpoint.trim(), providerWallet: draft.providerWallet.trim(), customerWallet: publicKey.toBase58(), monitorCount: MONITOR_COUNT })
      navigate(`/sla/${created.id}`)
    } catch (cause) {
      setSubmitError(cause instanceof Error ? cause.message : 'Could not create the agreement.')
    } finally { setSubmitting(false) }
  }

  return <div className="page-stack">
    <div className="page-heading create-heading"><div><Link to="/" className="back-link"><ArrowLeft size={15} /> Back to dashboard</Link><div className="eyebrow"><span className="eyebrow-line" /> NEW AGREEMENT</div><h1>Create an SLA</h1><p>Define your service standard and monitoring terms.</p></div></div>
    <div className="form-layout">
      <form id="create-sla-form" onSubmit={handleSubmit} className="surface form-surface" noValidate>
        <div className="form-section"><div className="form-section-heading"><span className="form-step">01</span><div><h2>Service details</h2><p>What API are you holding accountable?</p></div></div>
          <div className="field-grid"><div className="field"><label htmlFor="name">Agreement name</label><input id="name" value={draft.name} onChange={(e) => update('name', e.target.value)} placeholder="e.g. Payments API" aria-invalid={Boolean(errors.name)} /><small>{errors.name || 'A name to identify this agreement.'}</small></div><div className="field"><label htmlFor="endpoint">API endpoint</label><input id="endpoint" type="url" value={draft.endpoint} onChange={(e) => update('endpoint', e.target.value)} placeholder="https://api.example.com/health" aria-invalid={Boolean(errors.endpoint)} /><small>{errors.endpoint || 'The HTTPS endpoint for this agreement.'}</small></div></div>
          <div className="field"><label htmlFor="provider">Provider wallet address</label><input id="provider" value={draft.providerWallet} onChange={(e) => update('providerWallet', e.target.value)} placeholder="Enter a Solana wallet address" aria-invalid={Boolean(errors.providerWallet)} /><small>{errors.providerWallet || 'The provider contributes a guarantee and receives payment if the SLA is met.'}</small></div>
        </div>
        <div className="form-section"><div className="form-section-heading"><span className="form-step">02</span><div><h2>Agreement terms</h2><p>Set the funds and the performance threshold.</p></div></div>
          <div className="field-grid"><div className="field"><label htmlFor="customer-payment">Customer service payment</label><div className="input-with-suffix"><input id="customer-payment" type="number" min="0.000000001" max="10000" step="1" value={draft.customerPaymentSol} onChange={(e) => update('customerPaymentSol', e.target.value)} aria-invalid={Boolean(errors.customerPaymentSol)} /><span>SOL</span></div><small>{errors.customerPaymentSol || 'Returned to the customer if the SLA is breached.'}</small></div><div className="field"><label htmlFor="provider-guarantee">Provider SLA guarantee</label><div className="input-with-suffix"><input id="provider-guarantee" type="number" min="0.000000001" max="10000" step="1" value={draft.providerGuaranteeSol} onChange={(e) => update('providerGuaranteeSol', e.target.value)} aria-invalid={Boolean(errors.providerGuaranteeSol)} /><span>SOL</span></div><small>{errors.providerGuaranteeSol || 'Returned to the provider when the SLA is met.'}</small></div></div>
          <div className="field-grid"><div className="field"><label htmlFor="uptime">Required uptime</label><div className="input-with-suffix"><input id="uptime" type="number" min="0.01" max="100" step="0.01" value={draft.requiredUptime} onChange={(e) => update('requiredUptime', Number(e.target.value))} aria-invalid={Boolean(errors.requiredUptime)} /><span>%</span></div><small>{errors.requiredUptime || 'Minimum acceptable uptime over the period.'}</small></div><div className="field"><label htmlFor="duration">SLA duration</label><select id="duration" value={draft.durationDays} onChange={(e) => update('durationDays', Number(e.target.value))} aria-invalid={Boolean(errors.durationDays)}>{durationOptions.map((option) => <option key={option.label} value={option.value}>{option.label}</option>)}</select><small>{errors.durationDays || 'Starts when both parties have funded escrow.'}</small></div></div>
        </div>
        {submitError && <div className="form-submit-error"><ErrorState message={submitError} /></div>}
        <div className="form-submit"><button className="button button-primary button-large" type="submit" disabled={submitting}>{submitting ? 'Creating agreement...' : 'Create SLA'}<ArrowRight size={17} /></button></div>
      </form>

      <aside className="summary-column"><div className="surface summary-card"><div className="summary-icon"><ShieldCheck size={21} /></div><h2>Agreement summary</h2><p className="summary-intro">Both parties contribute to the escrow.</p><div className="summary-divider" /><div className="summary-list"><div><span><LockKeyhole size={16} /> Customer payment</span><strong>{draft.customerPaymentSol ? formatSol(customerPayment) : '—'}</strong></div><div><span><ShieldCheck size={16} /> Provider guarantee</span><strong>{draft.providerGuaranteeSol ? formatSol(providerGuarantee) : '—'}</strong></div><div><span><Coins size={16} /> Total held</span><strong>{totalEscrow}</strong></div><div><span><Clock3 size={16} /> Duration</span><strong>{formatDuration(draft.durationDays)}</strong></div></div><div className="summary-rule"><span>SETTLEMENT RULE</span><p>If uptime is at least {draft.requiredUptime || 0}%, the provider receives the customer payment and gets its guarantee back. Otherwise, the customer receives their payment back plus the provider guarantee.</p></div></div></aside>
    </div>
  </div>
}
