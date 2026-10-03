import { mockConsensus, mockMonitors, mockObservations, mockSettlementResults, mockSLAs } from '../../mocks/data'
import type { ConsensusSnapshot, CreateSLAInput, Monitor, Observation, SLA } from '../../types'

// This is the sole data boundary for the UI. Replace these mock implementations
// with account reads and Anchor instructions when the program is available.
const STORAGE_KEY = 'slana.mock.slas.v1'
const delay = (ms = 280) => new Promise((resolve) => window.setTimeout(resolve, ms))

function readStored(): SLA[] {
  try {
    return JSON.parse(window.localStorage.getItem(STORAGE_KEY) || '[]') as SLA[]
  } catch {
    return []
  }
}

function writeStored(slas: SLA[]) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(slas))
}

function allSLAs(): SLA[] {
  const stored = readStored()
  const overrides = new Map(stored.map((sla) => [sla.id, sla]))
  return [...stored.filter((sla) => !mockSLAs.some((seed) => seed.id === sla.id)),
    ...mockSLAs.map((seed) => overrides.get(seed.id) ?? seed)].map((sla) => {
      if (sla.settlement.state !== 'pending' || new Date(sla.endAt).getTime() > Date.now()) return sla
      return { ...sla, settlement: { ...sla.settlement, state: 'ready' as const } }
    })
}

export const slaService = {
  async getSLAs(): Promise<SLA[]> {
    await delay()
    return allSLAs()
  },

  async getSLA(id: string): Promise<SLA | undefined> {
    await delay()
    return allSLAs().find((sla) => sla.id === id)
  },

  async getMonitors(): Promise<Monitor[]> {
    await delay()
    return mockMonitors
  },

  async getObservations(slaId: string): Promise<Observation[]> {
    await delay()
    return mockObservations.filter((observation) => observation.slaId === slaId)
  },

  async getConsensus(slaId: string): Promise<ConsensusSnapshot | undefined> {
    await delay()
    return mockConsensus[slaId]
  },

  async createSLA(input: CreateSLAInput): Promise<SLA> {
    await delay(550)
    const startAt = new Date()
    const endAt = new Date(startAt.getTime() + Math.round(input.durationDays * 86_400_000))
    const sla: SLA = {
      ...input,
      id: `sla-${crypto.randomUUID()}`,
      startAt: startAt.toISOString(),
      endAt: endAt.toISOString(),
      currentUptime: 0,
      successfulChecks: 0,
      failedChecks: 0,
      status: 'pending',
      history: [],
      timeline: [],
      settlement: { state: 'pending' },
    }
    writeStored([sla, ...readStored()])
    return sla
  },

  async settleSLA(id: string): Promise<SLA> {
    await delay(750)
    const sla = allSLAs().find((item) => item.id === id)
    if (!sla) throw new Error('SLA not found.')
    if (new Date(sla.endAt).getTime() > Date.now()) throw new Error('This SLA has not ended yet.')
    if (sla.settlement.state === 'settled') return sla

    // Mock-only fixture. The real implementation must submit the settlement
    // instruction and reread the program account for its authoritative result.
    const result = mockSettlementResults[id]
    const settled: SLA = {
      ...sla,
      status: 'completed',
      settlement: {
        ...sla.settlement,
        state: 'settled',
        actualRecipient: result?.recipient,
        transaction: result?.transaction,
        settledAt: new Date().toISOString(),
      },
    }
    writeStored([settled, ...readStored().filter((item) => item.id !== id)])
    return settled
  },
}
