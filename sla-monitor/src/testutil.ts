import { vi } from 'vitest'
import type { AssignedSla, ProgramClient } from './client.js'

export const SLA_ADDR = '11111111111111111111111111111112'
export const ME = 'MonitorSelf1111111111111111111111111111111'

/** start=1000, window 100s, interval 25s, 250s total => windows [1000,1100) [1100,1200) [1200,1250); grace 20s. */
export function makeSla(over: Partial<AssignedSla> = {}): AssignedSla {
  return {
    address: SLA_ADDR,
    endpoint: 'https://example.test/health',
    timeoutMs: 1000,
    startTs: 1000,
    endTs: 1250,
    windowSecs: 100,
    checkIntervalSecs: 25,
    totalWindows: 3,
    reportGraceSecs: 20,
    nextWindowToFinalize: 0,
    settled: false,
    monitors: [ME, 'Other11111111111111111111111111111111111111'],
    ...over,
  }
}

export function mockClient(slas: AssignedSla[] = [makeSla()]) {
  const client = {
    fetchAssignedSlas: vi.fn(async () => slas),
    fetchWindowReportPayer: vi.fn(async (): Promise<string | null> => null),
    submitReport: vi.fn(async () => {}),
    finalizeWindow: vi.fn(async () => {}),
  } satisfies ProgramClient
  return client
}

export const rpcError = () => new Error('503 Service Unavailable')
export const programError = (code: number) => Object.assign(new Error(`custom program error: 0x${code.toString(16)}`), {})
