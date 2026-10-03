import { PublicKey, SystemProgram, TransactionInstruction } from '@solana/web3.js'
import { Buffer } from 'buffer'

/** Smallest escrow `create_deal` accepts (the program's `MIN_DEAL_LAMPORTS`). */
export const MIN_DEAL_LAMPORTS = 1_000_000n

/** Longest uptime window `create_deal` accepts, in seconds (the program's `MAX_DEAL_DURATION_SECONDS`). */
export const MAX_DEAL_DURATION_SECONDS = 86_400n

/** Seconds after the window ends before the payer may `cancel_deal` (the program's `CANCEL_TIMEOUT_SECONDS`). */
export const CANCEL_TIMEOUT_SECONDS = 600

/** First 8 bytes of sha256("global:cancel_deal"), from the program's IDL. */
const CANCEL_DEAL_DISCRIMINATOR = [158, 86, 193, 45, 168, 111, 48, 29]

/** First 8 bytes of sha256("global:create_deal"), from the program's IDL. */
const CREATE_DEAL_DISCRIMINATOR = [198, 212, 144, 151, 97, 56, 149, 113]

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

export interface CreateDealParams {
  /** The uptime_deal program. */
  programId: PublicKey
  /** Signs and funds the escrow and the account rent. */
  payer: PublicKey
  /** Paid the escrow when uptime is above 99%. */
  recipient: PublicKey
  /** The only key allowed to settle the deal. */
  oracle: PublicKey
  /** Payer-chosen id that makes the deal address unique. */
  dealId: bigint
  /** Lamports to lock; at least `MIN_DEAL_LAMPORTS`. */
  amountLamports: bigint
  /** Length of the uptime window in seconds, from 1 to `MAX_DEAL_DURATION_SECONDS`. */
  durationSeconds: bigint
}

/**
 * Builds the `create_deal` instruction, which locks the escrow in a new deal account.
 *
 * @param params the deal parties, id, amount and window length
 * @returns the instruction, with accounts in the program's order: payer, recipient, oracle, deal, system program
 * @throws RangeError when the duration is outside 1..`MAX_DEAL_DURATION_SECONDS`
 */
export function createDealInstruction(params: CreateDealParams): TransactionInstruction {
  if (params.durationSeconds < 1n || params.durationSeconds > MAX_DEAL_DURATION_SECONDS) {
    throw new RangeError(`The window must be 1 to ${MAX_DEAL_DURATION_SECONDS} seconds.`)
  }
  const data = new Uint8Array(32)
  data.set(CREATE_DEAL_DISCRIMINATOR, 0)
  data.set(u64le(params.dealId), 8)
  data.set(u64le(params.amountLamports), 16)
  data.set(u64le(params.durationSeconds), 24)
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

export interface CancelDealParams {
  /** The uptime_deal program. */
  programId: PublicKey
  /** The deal's payer; signs and receives the escrow and rent back. */
  payer: PublicKey
  /** The deal account to close. */
  deal: PublicKey
}

/**
 * Builds the `cancel_deal` instruction, which refunds the escrow and closes the deal once the
 * window plus `CANCEL_TIMEOUT_SECONDS` has passed on chain.
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
