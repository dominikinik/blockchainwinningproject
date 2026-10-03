/** 256-bit slot bitmaps, LSB first: slot `j` is byte `j / 8`, bit `j % 8` (see SPEC.md). */
export const BITMAP_BYTES = 32
export const MAX_SLOTS = BITMAP_BYTES * 8

export function newBitmap(): Uint8Array {
  return new Uint8Array(BITMAP_BYTES)
}

function check(slot: number): void {
  if (!Number.isInteger(slot) || slot < 0 || slot >= MAX_SLOTS) {
    throw new RangeError(`slot ${slot} out of range 0..${MAX_SLOTS - 1}`)
  }
}

export function setBit(bitmap: Uint8Array, slot: number): void {
  check(slot)
  bitmap[slot >> 3]! |= 1 << (slot & 7)
}

export function getBit(bitmap: Uint8Array, slot: number): boolean {
  check(slot)
  return (bitmap[slot >> 3]! & (1 << (slot & 7))) !== 0
}

export function isEmpty(bitmap: Uint8Array): boolean {
  return bitmap.every((b) => b === 0)
}

export function toHex(bitmap: Uint8Array): string {
  return Buffer.from(bitmap).toString('hex')
}

export function fromHex(hex: string): Uint8Array {
  if (!/^[0-9a-f]{64}$/i.test(hex)) throw new Error('bitmap hex must be 64 hex characters')
  return new Uint8Array(Buffer.from(hex, 'hex'))
}
