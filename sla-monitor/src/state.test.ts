import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { FileStateStore, type PendingReport } from './state.js'

const entry: PendingReport = { sla: 'S', windowIndex: 2, endTs: 10, deadline: 20, checked: '00'.repeat(32), up: '00'.repeat(32) }
const dir = () => mkdtempSync(join(tmpdir(), 'sla-monitor-'))

describe('FileStateStore', () => {
  it('persists across instances, writing atomically', () => {
    const path = join(dir(), 'sub', 'state.json')
    new FileStateStore(path).set(entry)
    expect(existsSync(`${path}.tmp`)).toBe(false)
    expect(new FileStateStore(path).get('S', 2)).toEqual(entry)
  })
  it('removes deleted entries from disk', () => {
    const path = join(dir(), 'state.json')
    const s = new FileStateStore(path)
    s.set(entry)
    s.delete('S', 2)
    expect(JSON.parse(readFileSync(path, 'utf8')).pending).toEqual([])
    expect(new FileStateStore(path).entries()).toEqual([])
  })
  it('starts empty when the file is missing', () => {
    expect(new FileStateStore(join(dir(), 'none.json')).entries()).toEqual([])
  })
  it('starts empty and warns when the file is corrupt', () => {
    const path = join(dir(), 'state.json')
    writeFileSync(path, '{not json')
    const warns: string[] = []
    expect(new FileStateStore(path, (m) => warns.push(m)).entries()).toEqual([])
    expect(warns).toHaveLength(1)
  })
  it('skips malformed entries', () => {
    const path = join(dir(), 'state.json')
    writeFileSync(path, JSON.stringify({ pending: [entry, { sla: 1 }] }))
    expect(new FileStateStore(path).entries()).toEqual([entry])
  })
})
