import type { ConsensusSnapshot, Monitor, Observation, SLA, UptimeBucket } from '../types'

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString()
const ahead = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString()

const paymentsHistory: UptimeBucket[] = [
  { label: 'Mon', uptime: 100, checks: 288, failed: 0 },
  { label: 'Tue', uptime: 99.96, checks: 288, failed: 1 },
  { label: 'Wed', uptime: 100, checks: 288, failed: 0 },
  { label: 'Thu', uptime: 99.96, checks: 288, failed: 1 },
  { label: 'Fri', uptime: 100, checks: 288, failed: 0 },
  { label: 'Sat', uptime: 100, checks: 288, failed: 0 },
  { label: 'Sun', uptime: 100, checks: 288, failed: 0 },
]
const analyticsHistory: UptimeBucket[] = [
  { label: 'Mon', uptime: 100, checks: 288, failed: 0 },
  { label: 'Tue', uptime: 99.98, checks: 288, failed: 1 },
  { label: 'Wed', uptime: 100, checks: 288, failed: 0 },
  { label: 'Thu', uptime: 99.82, checks: 288, failed: 5 },
  { label: 'Fri', uptime: 99.96, checks: 288, failed: 1 },
  { label: 'Sat', uptime: 99.89, checks: 288, failed: 3 },
  { label: 'Sun', uptime: 99.91, checks: 288, failed: 2 },
]
const emailHistory: UptimeBucket[] = [
  { label: 'Mon', uptime: 99.64, checks: 288, failed: 1 },
  { label: 'Tue', uptime: 98.96, checks: 288, failed: 3 },
  { label: 'Wed', uptime: 99.31, checks: 288, failed: 2 },
  { label: 'Thu', uptime: 97.92, checks: 288, failed: 6 },
  { label: 'Fri', uptime: 98.61, checks: 288, failed: 4 },
  { label: 'Sat', uptime: 99.3, checks: 288, failed: 2 },
  { label: 'Sun', uptime: 98.72, checks: 288, failed: 4 },
]

const customerWallet = '8xF2mTtX4k9Pjrv7EQ58R8v5J74y2CVxq9HZ1jK31Qz'

export const mockSLAs: SLA[] = [
  {
    id: 'payments-api', name: 'Payments API', endpoint: 'https://api.example.com/health',
    customerWallet, providerWallet: '7gJ4Ys6a3k8F5r2Dp9wH1nQ4cV6tL3mZ8xA2bR5e9PqK',
    customerPaymentSol: 10, providerGuaranteeSol: 2, requiredUptime: 99.9, currentUptime: 99.97,
    successfulChecks: 1438, failedChecks: 2, startAt: ago(2 * 24 * 60 + 12 * 60),
    endAt: ahead(4 * 24 * 60 + 12 * 60), durationDays: 7, checkIntervalMinutes: 5,
    timeoutMs: 2000, consensusRequired: 1, monitorCount: 1, status: 'healthy',
    history: paymentsHistory,
    timeline: Array.from({ length: 72 }, (_, i) => [19, 54].includes(i) ? 'down' : 'up'),
    settlement: { state: 'pending', projectionRecipient: 'provider', projectionAmountSol: 12 },
  },
  {
    id: 'analytics-api', name: 'Analytics API', endpoint: 'https://analytics.orbit.dev/status',
    customerWallet, providerWallet: '3WnEUhdVZyjdwyfkRdJAsPQ9j6mD34vXNrESZ6G99VRM',
    customerPaymentSol: 25, providerGuaranteeSol: 5, requiredUptime: 99.99, currentUptime: 99.91,
    successfulChecks: 2902, failedChecks: 3, startAt: ago(4 * 24 * 60 + 21 * 60),
    endAt: ahead(2 * 24 * 60 + 3 * 60), durationDays: 7, checkIntervalMinutes: 2,
    timeoutMs: 1500, consensusRequired: 1, monitorCount: 1, status: 'at-risk',
    history: analyticsHistory,
    timeline: Array.from({ length: 72 }, (_, i) => [7, 22, 23, 24, 41, 55, 56, 65].includes(i) ? 'down' : 'up'),
    settlement: { state: 'pending', projectionRecipient: 'customer', projectionAmountSol: 30 },
  },
  {
    id: 'email-api', name: 'Email API', endpoint: 'https://mail.atlas.io/healthz',
    customerWallet, providerWallet: '9vF3kA7qW2mL8jC4sR5xH1nD6pT9bY3eK7uG2zQ4aS81',
    customerPaymentSol: 5, providerGuaranteeSol: 1, requiredUptime: 99.5, currentUptime: 98.72,
    successfulChecks: 1240, failedChecks: 16, startAt: ago(7 * 24 * 60 + 47),
    endAt: ago(47), durationDays: 7, checkIntervalMinutes: 5,
    timeoutMs: 2500, consensusRequired: 1, monitorCount: 1, status: 'violated',
    history: emailHistory,
    timeline: Array.from({ length: 72 }, (_, i) => [2, 7, 12, 16, 17, 23, 24, 25, 32, 39, 40, 41, 50, 55, 61, 66].includes(i) ? 'down' : 'up'),
    settlement: { state: 'ready', projectionRecipient: 'customer', projectionAmountSol: 6 },
  },
]

export const mockMonitors: Monitor[] = [
  { id: 'a', name: 'Monitoring server', wallet: '8xF2mTtX4k9Pjrv7EQ58R8v5J74y2CVxq9HZ1jK31Qz', status: 'online', observations: 14281, agreementRate: 99.8, lastObservationAt: ago(0.2) },
]

const base58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
const mockSignature = (index: number) => Array.from({ length: 64 }, (_, position) => base58[(index * 19 + position * 37 + position * position * 7) % base58.length]).join('')

export const mockObservations: Observation[] = Array.from({ length: 18 }, (_, index) => ({
  id: `obs-${index}`,
  slaId: ['payments-api', 'analytics-api', 'email-api'][Math.floor(index / 6)],
  monitorId: 'a',
  timestamp: ago(index % 6 * 5 + Math.floor(index / 6) * 11),
  result: index % 6 === 2 ? 'down' : 'up',
  latencyMs: index % 6 === 2 ? null : [142, 167, 0, 151, 189, 154][index % 6],
  transaction: mockSignature(index),
}))

export const mockConsensus: Record<string, ConsensusSnapshot> = {
  'payments-api': {
    readings: [{ monitorId: 'a', result: 'up', latencyMs: 142 }],
    upCount: 1, totalCount: 1, result: 'up',
  },
  'analytics-api': {
    readings: [{ monitorId: 'a', result: 'up', latencyMs: 180 }],
    upCount: 1, totalCount: 1, result: 'up',
  },
  'email-api': {
    readings: [{ monitorId: 'a', result: 'down', latencyMs: null }],
    upCount: 0, totalCount: 1, result: 'down',
  },
}

// Program outcomes are fixtures in demo mode. The UI never derives them from uptime.
export const mockSettlementResults: Record<string, { recipient: 'provider' | 'customer'; transaction: string }> = {
  'email-api': {
    recipient: 'customer',
    transaction: mockSignature(42),
  },
}
