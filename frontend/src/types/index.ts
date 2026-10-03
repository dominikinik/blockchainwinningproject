export type SLAStatus = 'pending' | 'healthy' | 'at-risk' | 'violated' | 'completed'
export type ObservationResult = 'up' | 'down'
export type MonitorStatus = 'online' | 'offline'

export interface UptimeBucket {
  label: string
  uptime: number
  checks: number
  failed: number
}

export interface Settlement {
  state: 'pending' | 'ready' | 'settled'
  projectionRecipient?: 'provider' | 'customer'
  projectionAmountSol?: number
  actualRecipient?: 'provider' | 'customer'
  transaction?: string
  settledAt?: string
}

export interface SLA {
  id: string
  name: string
  endpoint: string
  customerWallet: string
  providerWallet: string
  customerPaymentSol: number
  providerGuaranteeSol: number
  requiredUptime: number
  currentUptime: number
  successfulChecks: number
  failedChecks: number
  startAt: string
  endAt: string
  durationDays: number
  consensusRequired: number
  monitorCount: number
  status: SLAStatus
  history: UptimeBucket[]
  timeline: ObservationResult[]
  settlement: Settlement
}

export interface Monitor {
  id: string
  name: string
  wallet: string
  status: MonitorStatus
  observations: number
  agreementRate: number
  lastObservationAt: string
}

export interface Observation {
  id: string
  slaId: string
  monitorId: string
  timestamp: string
  result: ObservationResult
  latencyMs: number | null
  transaction: string
}

export interface MonitorReading {
  monitorId: string
  result: ObservationResult
  latencyMs: number | null
}

export interface ConsensusSnapshot {
  readings: MonitorReading[]
  upCount: number
  totalCount: number
  result: ObservationResult
}

export interface CreateSLAInput {
  name: string
  endpoint: string
  customerWallet: string
  providerWallet: string
  customerPaymentSol: number
  providerGuaranteeSol: number
  requiredUptime: number
  durationDays: number
  consensusRequired: number
  monitorCount: number
}
