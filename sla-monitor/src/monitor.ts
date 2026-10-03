import { fromHex, getBit, newBitmap, setBit, toHex } from './bitmap.js'
import { checkEndpoint, type FetchFn } from './checker.js'
import type { AssignedSla, ProgramClient } from './client.js'
import { programErrorCode, withRetry, DEFAULT_RETRY, type RetryOptions } from './retry.js'
import { currentSlot, windowBounds } from './schedule.js'
import type { StateStore } from './state.js'

export const ERR_REPORT_DEADLINE_PASSED = 6020
export const ERR_DUPLICATE_REPORT = 6021

export interface MonitorDeps {
  client: ProgramClient
  store: StateStore
  fetch: FetchFn
  /** Unix seconds. */
  now: () => number
  sleep: (ms: number) => Promise<void>
  log?: (msg: string) => void
  retry?: Partial<Omit<RetryOptions, 'sleep'>>
  /** This node's wallet (base58): the payer for finalize when no WindowReport exists. */
  self: string
}

/** Cap on finalize transactions per SLA per run, so one run stays bounded. */
const MAX_FINALIZE_PER_RUN = 10

export class Monitor {
  private slas: AssignedSla[] = []
  private inflight = new Set<string>()
  private retryOpts: RetryOptions
  private log: (msg: string) => void

  constructor(private d: MonitorDeps) {
    this.retryOpts = { ...DEFAULT_RETRY, ...d.retry, sleep: d.sleep }
    this.log = d.log ?? (() => {})
  }

  private rpc<T>(fn: () => Promise<T>): Promise<T> {
    return withRetry(fn, this.retryOpts)
  }

  get assigned(): readonly AssignedSla[] {
    return this.slas
  }

  /** Reloads the SLAs that list this monitor. On failure keeps the previous list. */
  async refresh(): Promise<void> {
    try {
      this.slas = await this.rpc(() => this.d.client.fetchAssignedSlas())
    } catch (err) {
      this.log(`refresh failed: ${(err as Error).message}`)
    }
  }

  /** Runs the check due now for each live SLA that has not been checked for this slot. */
  async runChecks(): Promise<void> {
    const now = this.d.now()
    const jobs: Promise<void>[] = []
    for (const sla of this.slas) {
      if (sla.settled) continue
      const cur = currentSlot(sla, now)
      if (!cur) continue // not started, or ended: never check
      const { window, slot } = cur
      const id = `${sla.address}:${window.index}:${slot}`
      const entry = this.d.store.get(sla.address, window.index)
      if (this.inflight.has(id) || (entry && getBit(fromHex(entry.checked), slot))) continue
      this.inflight.add(id)
      jobs.push(
        this.checkSlot(sla, window.index, window.end, slot).finally(() => this.inflight.delete(id)),
      )
    }
    await Promise.all(jobs)
  }

  private async checkSlot(sla: AssignedSla, windowIndex: number, windowEnd: number, slot: number) {
    const up = await checkEndpoint(this.d.fetch, sla.endpoint, sla.timeoutMs)
    const prev = this.d.store.get(sla.address, windowIndex)
    const checked = prev ? fromHex(prev.checked) : newBitmap()
    const upMap = prev ? fromHex(prev.up) : newBitmap()
    setBit(checked, slot)
    if (up) setBit(upMap, slot)
    this.d.store.set({
      sla: sla.address,
      windowIndex,
      endTs: windowEnd,
      deadline: windowEnd + sla.reportGraceSecs,
      checked: toHex(checked),
      up: toHex(upMap),
    })
    this.log(`checked ${sla.address} w${windowIndex} slot ${slot}: ${up ? 'UP' : 'DOWN'}`)
  }

  /** Submits reports for ended windows still inside their deadline; drops expired entries. */
  async runReports(): Promise<void> {
    for (const e of this.d.store.entries()) {
      const now = this.d.now()
      if (now >= e.deadline) {
        this.log(`dropping expired report ${e.sla} w${e.windowIndex}`)
        this.d.store.delete(e.sla, e.windowIndex)
        continue
      }
      if (now < e.endTs) continue
      try {
        await this.rpc(() =>
          this.d.client.submitReport(e.sla, e.windowIndex, fromHex(e.checked), fromHex(e.up)),
        )
        this.d.store.delete(e.sla, e.windowIndex)
      } catch (err) {
        const code = programErrorCode(err)
        if (code === ERR_DUPLICATE_REPORT) {
          this.d.store.delete(e.sla, e.windowIndex) // already on-chain: success
        } else if (code === ERR_REPORT_DEADLINE_PASSED) {
          this.log(`deadline passed for ${e.sla} w${e.windowIndex}, dropping`)
          this.d.store.delete(e.sla, e.windowIndex)
        } else {
          this.log(`submit failed for ${e.sla} w${e.windowIndex}: ${(err as Error).message}`)
        }
      }
    }
  }

  /** Cranks `finalize_window` for every assigned SLA whose next window is past its deadline. */
  async runFinalize(): Promise<void> {
    for (const sla of this.slas) {
      if (sla.settled) continue
      let w = sla.nextWindowToFinalize
      for (let n = 0; n < MAX_FINALIZE_PER_RUN && w < sla.totalWindows; n++, w++) {
        const b = windowBounds(sla, w)
        if (this.d.now() < b.end + sla.reportGraceSecs) break
        try {
          const payer = (await this.rpc(() => this.d.client.fetchWindowReportPayer(sla.address, w))) ?? this.d.self
          await this.rpc(() => this.d.client.finalizeWindow(sla.address, w, payer, sla.monitors))
          sla.nextWindowToFinalize = w + 1
        } catch (err) {
          this.log(`finalize failed for ${sla.address} w${w}: ${(err as Error).message}`)
          break // strictly in order: don't skip ahead
        }
      }
    }
  }
}
