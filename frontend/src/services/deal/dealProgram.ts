import { PublicKey, SystemProgram, TransactionInstruction } from '@solana/web3.js'
import { Buffer } from 'buffer'

/** Smallest customer payment `create_deal` accepts (the program's `MIN_DEAL_LAMPORTS`). */
export const MIN_DEAL_LAMPORTS = 1_000_000n

/** Longest uptime window `create_deal` accepts, in seconds (the program's `MAX_DEAL_DURATION_SECONDS`). */
export const MAX_DEAL_DURATION_SECONDS = 86_400n

/** Most monitoring rounds a deal may have (the program's `MAX_ROUNDS`). */
export const MAX_ROUNDS = 8_192

/** Uptime thresholds are in basis points: 10,000 = 100% (the program's `BPS_DENOMINATOR`). */
export const BPS_DENOMINATOR = 10_000

/**
 * Seconds after the window ends during which observations still land; settlement opens exactly then
 * (the program's `OBSERVATION_GRACE_SECONDS`).
 */
export const OBSERVATION_GRACE_SECONDS = 10

/** First 8 bytes of sha256("global:<instruction>") and sha256("event:<Event>"), from the program's IDL. */
const CREATE_DEAL_DISCRIMINATOR = [198, 212, 144, 151, 97, 56, 149, 113]
const ACCEPT_DEAL_DISCRIMINATOR = [76, 156, 34, 30, 129, 136, 76, 244]
const SETTLE_DEAL_DISCRIMINATOR = [28, 10, 168, 174, 203, 149, 134, 54]
const CANCEL_DEAL_DISCRIMINATOR = [158, 86, 193, 45, 168, 111, 48, 29]
const DEAL_ACCOUNT_DISCRIMINATOR = [125, 223, 160, 234, 71, 162, 182, 219]
const DEAL_SETTLED_DISCRIMINATOR = [41, 213, 235, 64, 55, 168, 51, 76]
const DEAL_CANCELLED_DISCRIMINATOR = [229, 189, 86, 176, 134, 151, 43, 152]

/** Bytes of a `Deal` before its bitmap: discriminator, fields, and the bitmap's length prefix. */
const DEAL_FIXED_SIZE = 8 + 32 * 3 + 8 * 4 + 1 + 8 * 3 + 2 + 4 * 3 + 1 + 4

const DEAL_SEED = new TextEncoder().encode('deal')

/**
 * Encodes an unsigned 64-bit integer the way Borsh and the PDA seeds do.
 *
 * @param value an integer from 0 to 2^64 - 1
 * @returns the 8 little-endian bytes
 * @throws RangeError when the value doesn't fit in a u64
 */
export function u64le(value: bigint): Uint8Array {
  if (value < 0n || value >= 1n << 64n) throw new RangeError(`${value} does not fit in a u64`)
  const bytes = new Uint8Array(8)
  new DataView(bytes.buffer).setBigUint64(0, value, true)
  return bytes
}

/**
 * Derives the address of a deal.
 *
 * @param programId the uptime_deal program
 * @param payer the wallet that funds the deal
 * @param dealId the payer-chosen deal id
 * @returns the `Deal` PDA for seeds `["deal", payer, dealId (u64 LE)]`
 */
export function dealAddress(programId: PublicKey, payer: PublicKey, dealId: bigint): PublicKey {
  return PublicKey.findProgramAddressSync([DEAL_SEED, payer.toBytes(), u64le(dealId)], programId)[0]
}

/**
 * Splits a window into monitoring rounds the way `create_deal` does.
 *
 * @param durationSeconds the window length
 * @param checkIntervalSeconds the length of one round
 * @returns the number of rounds, or null when the interval doesn't divide the window into 1..`MAX_ROUNDS`
 */
export function totalRounds(durationSeconds: number, checkIntervalSeconds: number): number | null {
  if (!Number.isInteger(durationSeconds) || !Number.isInteger(checkIntervalSeconds) || checkIntervalSeconds < 1) return null
  if (durationSeconds % checkIntervalSeconds !== 0) return null
  const rounds = durationSeconds / checkIntervalSeconds
  return rounds >= 1 && rounds <= MAX_ROUNDS ? rounds : null
}

/**
 * The fewest UP rounds that meet a threshold, i.e. the smallest `up` with
 * `up * 10_000 >= minUptimeBps * totalRounds` (the program's rule). For display only: the program decides.
 *
 * @param totalRounds rounds in the window
 * @param minUptimeBps the threshold in basis points
 * @returns the required number of UP rounds
 */
export function requiredUpRounds(totalRounds: number, minUptimeBps: number): number {
  return Math.ceil((minUptimeBps * totalRounds) / BPS_DENOMINATOR)
}

export interface CreateDealParams {
  /** The uptime_deal program. */
  programId: PublicKey
  /** The customer: signs and funds the payment and the account rent. */
  payer: PublicKey
  /** The provider: wins the whole escrow when the SLA is met. */
  recipient: PublicKey
  /** The only key allowed to record observations. */
  oracle: PublicKey
  /** Payer-chosen id that makes the deal address unique. */
  dealId: bigint
  /** The customer payment; at least `MIN_DEAL_LAMPORTS`. */
  amountLamports: bigint
  /** The provider guarantee to lock with `accept_deal`; 0 starts the window at once. */
  providerStakeLamports: bigint
  /** Length of the uptime window in seconds, from 1 to `MAX_DEAL_DURATION_SECONDS`. */
  durationSeconds: bigint
  /** Length of one monitoring round; must divide the window into 1..`MAX_ROUNDS` rounds. */
  checkIntervalSeconds: bigint
  /** Required uptime in basis points (1..10,000). */
  minUptimeBps: number
}

/**
 * Builds the `create_deal` instruction, which locks the customer payment in a new deal account.
 *
 * @param params the parties, id and terms
 * @returns the instruction, with accounts in the program's order: payer, recipient, oracle, deal, system program
 * @throws RangeError when the duration, interval or threshold is out of range
 */
export function createDealInstruction(params: CreateDealParams): TransactionInstruction {
  if (params.durationSeconds < 1n || params.durationSeconds > MAX_DEAL_DURATION_SECONDS) {
    throw new RangeError(`The window must be 1 to ${MAX_DEAL_DURATION_SECONDS} seconds.`)
  }
  if (totalRounds(Number(params.durationSeconds), Number(params.checkIntervalSeconds)) === null) {
    throw new RangeError(`The check interval must divide the window into 1 to ${MAX_ROUNDS} rounds.`)
  }
  if (!Number.isInteger(params.minUptimeBps) || params.minUptimeBps < 1 || params.minUptimeBps > BPS_DENOMINATOR) {
    throw new RangeError('The minimum uptime must be 0.01% to 100%.')
  }
  const data = new Uint8Array(50)
  data.set(CREATE_DEAL_DISCRIMINATOR, 0)
  data.set(u64le(params.dealId), 8)
  data.set(u64le(params.amountLamports), 16)
  data.set(u64le(params.providerStakeLamports), 24)
  data.set(u64le(params.durationSeconds), 32)
  data.set(u64le(params.checkIntervalSeconds), 40)
  new DataView(data.buffer).setUint16(48, params.minUptimeBps, true)
  return new TransactionInstruction({
    programId: params.programId,
    keys: [
      { pubkey: params.payer, isSigner: true, isWritable: true },
      { pubkey: params.recipient, isSigner: false, isWritable: false },
      { pubkey: params.oracle, isSigner: false, isWritable: false },
      { pubkey: dealAddress(params.programId, params.payer, params.dealId), isSigner: false, isWritable: true },
      { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
    ],
    data: Buffer.from(data),
  })
}

/**
 * Builds the `accept_deal` instruction, which locks the provider guarantee and starts the window.
 *
 * @param params the program, the recipient (signs and pays the guarantee) and the deal address
 * @returns the instruction, with accounts in the program's order: recipient, deal, system program
 */
export function acceptDealInstruction(params: { programId: PublicKey; recipient: PublicKey; deal: PublicKey }): TransactionInstruction {
  return new TransactionInstruction({
    programId: params.programId,
    keys: [
      { pubkey: params.recipient, isSigner: true, isWritable: true },
      { pubkey: params.deal, isSigner: false, isWritable: true },
      { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
    ],
    data: Buffer.from(ACCEPT_DEAL_DISCRIMINATOR),
  })
}

export interface SettleDealParams {
  /** The uptime_deal program. */
  programId: PublicKey
  /** Anyone: signs and pays the fee. */
  caller: PublicKey
  /** The deal account to settle. */
  deal: PublicKey
  /** The deal's payer (gets the rent, and the escrow on a breach). */
  payer: PublicKey
  /** The deal's recipient (gets the escrow when the SLA is met). */
  recipient: PublicKey
}

/**
 * Builds the `settle_deal` instruction. It carries no figures: the program judges the SLA from its own
 * counters, so any wallet can trigger it once the window and the observation grace are over.
 *
 * @param params the program, the caller and the deal's accounts
 * @returns the instruction, with accounts in the program's order: caller, deal, payer, recipient
 */
export function settleDealInstruction(params: SettleDealParams): TransactionInstruction {
  return new TransactionInstruction({
    programId: params.programId,
    keys: [
      { pubkey: params.caller, isSigner: true, isWritable: false },
      { pubkey: params.deal, isSigner: false, isWritable: true },
      { pubkey: params.payer, isSigner: false, isWritable: true },
      { pubkey: params.recipient, isSigner: false, isWritable: true },
    ],
    data: Buffer.from(SETTLE_DEAL_DISCRIMINATOR),
  })
}

export interface CancelDealParams {
  /** The uptime_deal program. */
  programId: PublicKey
  /** The deal's payer; signs and receives the payment and rent back. */
  payer: PublicKey
  /** The deal account to close. */
  deal: PublicKey
}

/**
 * Builds the `cancel_deal` instruction, which withdraws a deal the provider hasn't accepted yet.
 *
 * @param params the program, the payer and the deal address
 * @returns the instruction, with accounts in the program's order: payer, deal
 */
export function cancelDealInstruction(params: CancelDealParams): TransactionInstruction {
  return new TransactionInstruction({
    programId: params.programId,
    keys: [
      { pubkey: params.payer, isSigner: true, isWritable: true },
      { pubkey: params.deal, isSigner: false, isWritable: true },
    ],
    data: Buffer.from(CANCEL_DEAL_DISCRIMINATOR),
  })
}

/** A decoded `Deal` account: the terms and the authoritative SLA counters. */
export interface OnChainDeal {
  payer: string
  recipient: string
  oracle: string
  dealId: bigint
  amountLamports: bigint
  providerStakeLamports: bigint
  /** Chain time (unix seconds) by which the proposal must be accepted. */
  acceptDeadline: number
  /** False while the deal waits for the provider's guarantee. */
  active: boolean
  /** Chain time (unix seconds) the window started; null until active. */
  startsAt: number | null
  durationSeconds: number
  checkIntervalSeconds: number
  minUptimeBps: number
  totalRounds: number
  upChecks: number
  downChecks: number
  /** One bit per round, set once that round is recorded. */
  recorded: Uint8Array
}

/**
 * Decodes `Deal` account data.
 *
 * @param data the raw account data
 * @returns the decoded deal
 * @throws Error when the data is too short or isn't a `Deal`
 */
export function decodeDeal(data: Uint8Array): OnChainDeal {
  if (data.length < DEAL_FIXED_SIZE || DEAL_ACCOUNT_DISCRIMINATOR.some((b, i) => data[i] !== b)) {
    throw new Error('Account is not an uptime_deal Deal')
  }
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength)
  let at = 8
  const key = () => { const k = new PublicKey(data.subarray(at, at + 32)).toBase58(); at += 32; return k }
  const u64 = () => { const v = view.getBigUint64(at, true); at += 8; return v }
  const i64 = () => { const v = view.getBigInt64(at, true); at += 8; return v }
  const u32 = () => { const v = view.getUint32(at, true); at += 4; return v }
  const payer = key(), recipient = key(), oracle = key()
  const dealId = u64(), amountLamports = u64(), providerStakeLamports = u64(), acceptDeadline = Number(i64())
  const active = data[at++] === 1
  const startsAt = i64()
  const durationSeconds = Number(u64()), checkIntervalSeconds = Number(u64())
  const minUptimeBps = view.getUint16(at, true); at += 2
  const total = u32(), upChecks = u32(), downChecks = u32()
  at += 1 // bump
  const bitmapLength = u32()
  if (bitmapLength > data.length - at) throw new Error('Deal bitmap is truncated')
  return {
    payer, recipient, oracle, dealId, amountLamports, providerStakeLamports, acceptDeadline, active,
    startsAt: active ? Number(startsAt) : null, durationSeconds, checkIntervalSeconds, minUptimeBps,
    totalRounds: total, upChecks, downChecks, recorded: data.slice(at, at + bitmapLength),
  }
}

/** How a deal account was closed, read from the program's event. */
export type DealOutcome =
  | { cancelled: false; paidToRecipient: boolean; upChecks: number; downChecks: number; totalRounds: number; minUptimeBps: number; payoutLamports: bigint }
  | { cancelled: true }

/**
 * Finds the `DealSettled` or `DealCancelled` event of one deal in transaction logs.
 *
 * @param logs the transaction's log lines
 * @param deal Base58 deal address the event must name
 * @returns how the deal was closed, or null if the logs hold no such event for that deal
 */
export function closedBy(logs: string[], deal: string): DealOutcome | null {
  const dealBytes = new PublicKey(deal).toBytes()
  for (const line of logs) {
    if (!line.startsWith('Program data: ')) continue
    const event = Uint8Array.from(Buffer.from(line.slice('Program data: '.length).trim(), 'base64'))
    if (event.length < 40 || dealBytes.some((b, i) => event[8 + i] !== b)) continue
    const view = new DataView(event.buffer, event.byteOffset, event.byteLength)
    // Discriminator, deal, up_checks, down_checks, total_rounds, min_uptime_bps, paid_to_recipient, payout_lamports.
    if (event.length >= 40 + 12 + 2 + 1 + 8 && DEAL_SETTLED_DISCRIMINATOR.every((b, i) => event[i] === b)) {
      return {
        cancelled: false, upChecks: view.getUint32(40, true), downChecks: view.getUint32(44, true),
        totalRounds: view.getUint32(48, true), minUptimeBps: view.getUint16(52, true),
        paidToRecipient: event[54] !== 0, payoutLamports: view.getBigUint64(55, true),
      }
    }
    if (event.length >= 40 + 32 + 8 && DEAL_CANCELLED_DISCRIMINATOR.every((b, i) => event[i] === b)) return { cancelled: true }
  }
  return null
}
