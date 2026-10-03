import type { ReactNode } from 'react'
import { AlertCircle, ArrowRight, Check, CircleHelp, LoaderCircle } from 'lucide-react'
import type { SLAStatus } from '../types'

const statusLabels: Record<SLAStatus, string> = {
  pending: 'Pending', healthy: 'Healthy', 'at-risk': 'At Risk', violated: 'Violated', completed: 'Completed',
}

export function StatusBadge({ status }: { status: SLAStatus }) {
  return <span className={`status-badge status-${status}`}><span className="status-dot" />{statusLabels[status]}</span>
}

export function ResultBadge({ result }: { result: 'up' | 'down' }) {
  return <span className={`result-badge result-${result}`}><span className="status-dot" />{result.toUpperCase()}</span>
}

export function SectionHeading({ title, subtitle, action }: { title: string; subtitle?: string; action?: ReactNode }) {
  return <div className="section-heading"><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>{action}</div>
}

export function EmptyState({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return <div className="empty-state"><div className="empty-icon"><CircleHelp size={22} /></div><h3>{title}</h3><p>{description}</p>{action}</div>
}

export function ErrorState({ message, retry }: { message: string; retry?: () => void }) {
  return <div className="state-message error-message"><AlertCircle size={19} /><span>{message}</span>{retry && <button onClick={retry}>Try again <ArrowRight size={14} /></button>}</div>
}

export function LoadingState({ label = 'Loading data' }: { label?: string }) {
  return <div className="state-message loading-message"><LoaderCircle className="spin" size={20} />{label}...</div>
}

export function Notice({ children }: { children: ReactNode }) {
  return <div className="notice"><Check size={17} />{children}</div>
}
