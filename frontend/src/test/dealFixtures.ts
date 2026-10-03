import { Buffer } from 'buffer'
import type { PublicKey } from '@solana/web3.js'

/**
 * Builds `Deal` account bytes the way the uptime_deal program lays them out.
 *
 * @param fields the parties and any terms or counters to override (defaults: active, 10 one-second rounds at 99%)
 * @returns the raw account data
 */
export function dealBytes(fields: {
  payer: PublicKey; recipient: PublicKey; oracle: PublicKey; dealId?: bigint; amount?: bigint; stake?: bigint; active?: boolean
  startsAt?: bigint; duration?: bigint; interval?: bigint; minBps?: number; total?: number; up?: number; down?: number; recorded?: number[]
}): Uint8Array {
  const recorded = fields.recorded ?? [0, 0]
  const data = new Uint8Array(172 + recorded.length)
  const view = new DataView(data.buffer)
  data.set([125, 223, 160, 234, 71, 162, 182, 219], 0)
  data.set(fields.payer.toBytes(), 8)
  data.set(fields.recipient.toBytes(), 40)
  data.set(fields.oracle.toBytes(), 72)
  view.setBigUint64(104, fields.dealId ?? 1n, true)
  view.setBigUint64(112, fields.amount ?? 500_000_000n, true)
  view.setBigUint64(120, fields.stake ?? 0n, true)
  data[128] = fields.active === false ? 0 : 1
  view.setBigInt64(129, fields.startsAt ?? 1_790_000_000n, true)
  view.setBigUint64(137, fields.duration ?? 10n, true)
  view.setBigUint64(145, fields.interval ?? 1n, true)
  view.setUint16(153, fields.minBps ?? 9_900, true)
  view.setUint32(155, fields.total ?? 10, true)
  view.setUint32(159, fields.up ?? 0, true)
  view.setUint32(163, fields.down ?? 0, true)
  data[167] = 254
  view.setUint32(168, recorded.length, true)
  data.set(recorded, 172)
  return data
}

/**
 * The `Program data:` log line of a `DealSettled` event.
 *
 * @returns the log line for the given deal, counters and verdict
 */
export function settledLog(deal: PublicKey, up: number, down: number, total: number, paid: boolean, payout = 500_000_000n): string {
  const event = new Uint8Array(8 + 32 + 12 + 2 + 1 + 8)
  const view = new DataView(event.buffer)
  event.set([41, 213, 235, 64, 55, 168, 51, 76], 0)
  event.set(deal.toBytes(), 8)
  view.setUint32(40, up, true)
  view.setUint32(44, down, true)
  view.setUint32(48, total, true)
  view.setUint16(52, 9_900, true)
  event[54] = paid ? 1 : 0
  view.setBigUint64(55, payout, true)
  return `Program data: ${Buffer.from(event).toString('base64')}`
}

/**
 * The `Program data:` log line of a `DealCancelled` event.
 *
 * @returns the log line for the given deal and payer
 */
export function cancelledLog(deal: PublicKey, payer: PublicKey): string {
  const event = new Uint8Array(8 + 32 + 32 + 8)
  event.set([229, 189, 86, 176, 134, 151, 43, 152], 0)
  event.set(deal.toBytes(), 8)
  event.set(payer.toBytes(), 40)
  return `Program data: ${Buffer.from(event).toString('base64')}`
}
