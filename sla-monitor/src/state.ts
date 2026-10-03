import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

/** Observations for one (SLA, window) not yet reported on-chain. */
export interface PendingReport {
  sla: string
  windowIndex: number
  /** `end_i`: reportable from here. */
  endTs: number
  /** `end_i + G`: reportable strictly before this. */
  deadline: number
  /** 32-byte bitmaps as hex. */
  checked: string
  up: string
}

export interface StateStore {
  entries(): PendingReport[]
  get(sla: string, windowIndex: number): PendingReport | undefined
  set(entry: PendingReport): void
  delete(sla: string, windowIndex: number): void
}

const key = (sla: string, w: number) => `${sla}:${w}`

export class MemoryStateStore implements StateStore {
  protected map = new Map<string, PendingReport>()
  entries() {
    return [...this.map.values()]
  }
  get(sla: string, w: number) {
    return this.map.get(key(sla, w))
  }
  set(e: PendingReport) {
    this.map.set(key(e.sla, e.windowIndex), e)
    this.changed()
  }
  delete(sla: string, w: number) {
    if (this.map.delete(key(sla, w))) this.changed()
  }
  protected changed(): void {}
}

/** JSON file store. Every mutation is written through atomically (temp file + rename). */
export class FileStateStore extends MemoryStateStore {
  constructor(
    private path: string,
    private warn: (msg: string) => void = () => {},
  ) {
    super()
    this.load()
  }

  private load(): void {
    let raw: string
    try {
      raw = readFileSync(this.path, 'utf8')
    } catch {
      return // no state yet
    }
    try {
      const parsed = JSON.parse(raw) as { version?: number; pending?: PendingReport[] }
      for (const e of parsed.pending ?? []) {
        if (typeof e.sla === 'string' && Number.isInteger(e.windowIndex) && typeof e.checked === 'string') {
          this.map.set(key(e.sla, e.windowIndex), e)
        }
      }
    } catch (err) {
      this.warn(`ignoring unreadable state file ${this.path}: ${(err as Error).message}`)
    }
  }

  protected override changed(): void {
    mkdirSync(dirname(this.path), { recursive: true })
    const tmp = `${this.path}.tmp`
    writeFileSync(tmp, JSON.stringify({ version: 1, pending: this.entries() }, null, 2))
    renameSync(tmp, this.path)
  }
}
