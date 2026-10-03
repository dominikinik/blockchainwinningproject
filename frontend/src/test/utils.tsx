import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { SLA } from '../types'

export function renderAt(ui: ReactElement, path = '/', routePath = '*') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes><Route path={routePath} element={ui} /></Routes>
    </MemoryRouter>,
  )
}

export const WALLET = '8xF2mTtX4k9Pjrv7EQ58R8v5J74y2CVxq9HZ1jK31Qz'
export const PROVIDER = '9vF3kA7qW2mL8jC4sR5xH1nD6pT9bY3eK7uG2zQ4aS81'

export function makeSLA(overrides: Partial<SLA> = {}): SLA {
  const now = Date.now()
  return {
    id: 'test-sla', name: 'Test API', endpoint: 'https://api.test/health',
    customerWallet: WALLET, providerWallet: PROVIDER, customerPaymentSol: 10, providerGuaranteeSol: 2,
    requiredUptime: 99.9, currentUptime: 99.95, successfulChecks: 90, failedChecks: 10,
    startAt: new Date(now - 86_400_000).toISOString(), endAt: new Date(now + 86_400_000).toISOString(),
    durationDays: 7, consensusRequired: 3, monitorCount: 5,
    status: 'healthy', history: [], timeline: ['up', 'down'],
    settlement: { state: 'pending', projectionRecipient: 'provider', projectionAmountSol: 12 },
    ...overrides,
  }
}
