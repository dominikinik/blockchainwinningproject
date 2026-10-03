import { describe, expect, it } from 'vitest'
import { fromHex, getBit, isEmpty, newBitmap, setBit, toHex } from './bitmap.js'

describe('bitmap', () => {
  it('is 32 zero bytes when new', () => {
    const b = newBitmap()
    expect(b).toHaveLength(32)
    expect(isEmpty(b)).toBe(true)
  })
  it('sets bits LSB first: slot j is byte j/8, bit j%8', () => {
    const b = newBitmap()
    setBit(b, 0)
    setBit(b, 3)
    setBit(b, 8)
    setBit(b, 255)
    expect(b[0]).toBe(0b00001001)
    expect(b[1]).toBe(0b00000001)
    expect(b[31]).toBe(0b10000000)
    expect(getBit(b, 3)).toBe(true)
    expect(getBit(b, 4)).toBe(false)
  })
  it('rejects out-of-range slots', () => {
    expect(() => setBit(newBitmap(), 256)).toThrow(RangeError)
    expect(() => getBit(newBitmap(), -1)).toThrow(RangeError)
  })
  it('round-trips hex and rejects malformed hex', () => {
    const b = newBitmap()
    setBit(b, 9)
    expect(fromHex(toHex(b))).toEqual(b)
    expect(() => fromHex('abcd')).toThrow()
  })
})
