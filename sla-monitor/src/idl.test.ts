import { describe, expect, it } from 'vitest'
import { loadIdl } from './idl.js'

describe('copied sla IDL', () => {
  it('exposes the instructions the monitor node calls', () => {
    const names = loadIdl().instructions.map((ix) => ix.name)
    expect(names).toEqual(expect.arrayContaining(['submit_report', 'finalize_window']))
  })
})
