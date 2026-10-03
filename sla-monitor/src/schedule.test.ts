import { describe, expect, it } from 'vitest'
import { currentSlot, slotTime, windowBounds } from './schedule.js'
import { makeSla } from './testutil.js'

const sla = makeSla()

describe('schedule', () => {
  it('computes window bounds with a shorter last window', () => {
    expect(windowBounds(sla, 0)).toEqual({ index: 0, start: 1000, end: 1100, slots: 4 })
    expect(windowBounds(sla, 2)).toEqual({ index: 2, start: 1200, end: 1250, slots: 2 })
  })
  it('rounds the slot count up', () => {
    expect(windowBounds(makeSla({ checkIntervalSecs: 30 }), 0).slots).toBe(4) // ceil(100/30)
    expect(windowBounds(makeSla({ checkIntervalSecs: 30 }), 2).slots).toBe(2) // ceil(50/30)
  })
  it('rejects bad window indexes', () => {
    expect(() => windowBounds(sla, 3)).toThrow(RangeError)
    expect(() => windowBounds(sla, -1)).toThrow(RangeError)
  })
  it('gives slot times start_i + j*I', () => {
    expect(slotTime(sla, 1, 2)).toBe(1150)
  })
  it('finds the current slot at boundaries', () => {
    expect(currentSlot(sla, 999)).toBeNull()
    expect(currentSlot(sla, 1000)).toMatchObject({ slot: 0, window: { index: 0 } })
    expect(currentSlot(sla, 1024)).toMatchObject({ slot: 0 })
    expect(currentSlot(sla, 1099)).toMatchObject({ slot: 3, window: { index: 0 } })
    expect(currentSlot(sla, 1100)).toMatchObject({ slot: 0, window: { index: 1 } })
    expect(currentSlot(sla, 1249)).toMatchObject({ slot: 1, window: { index: 2 } })
    expect(currentSlot(sla, 1250)).toBeNull()
  })
})
