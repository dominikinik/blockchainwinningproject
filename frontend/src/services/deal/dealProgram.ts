import { PublicKey, SystemProgram, TransactionInstruction } from '@solana/web3.js'
import { Buffer } from 'buffer'

/** Smallest payment and smallest guarantee `create_deal` accepts (the program's `MIN_DEAL_LAMPORTS`). */
export const MIN_DEAL_LAMPORTS = 1_000_000n

/** Longest uptime window `create_deal` accepts, in seconds (the program's `MAX_DEAL_DURATION_SECONDS`). */
export const MAX_DEAL_DURATION_SECONDS = 86_400n

/** Seconds after the window ends before a party may `cancel_deal` an accepted deal (the program's `CANCEL_TIMEOUT_SECONDS`). */
export const CANCEL_TIMEOUT_SECONDS = 600

/** Seconds after `create_deal` during which the recipient may accept (the program's `ACCEPT_TIMEOUT_SECONDS`). */
export const ACCEPT_TIMEOUT_SECONDS = 86_400

/** First 8 bytes of sha256("global:accept_deal"), from the program's IDL. */
const ACCEPT_DEAL_DISCRIMINATOR = [76, 156, 34, 30, 129, 136, 76, 244]

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
 * @param payer the wallet that proposes the deal
 * @param dealId the payer-chosen deal id
 * @returns the `Deal` PDA for seeds `["deal", payer, dealId (u64 LE)]`
 */
export function dealAddress(programId: PublicKey, payer: PublicKey, dealId: bigint): PublicKey {
  return PublicKey.findProgramAddressSync([DEAL_SEED, payer.toBytes(), u64le(dealId)], programId)[0]
}

export interface CreateDealParams {
  /** The uptime_deal program. */
  programId: PublicKey
  /** Signs, pays its payment and the account rent. */
  payer: PublicKey
  /** Must accept the deal; receives both deposits when uptime is above 99%. */
  recipient: PublicKey
  /** The only key allowed to settle the deal. */
  oracle: PublicKey
  /** Payer-chosen id that makes the deal address unique. */
  dealId: bigint
  /** The payer's payment, locked now; at least `MIN_DEAL_LAMPORTS`. */
  amountLamports: bigint
  /** The recipient's guarantee, locked when it accepts; at least `MIN_DEAL_LAMPORTS`. */
  guaranteeLamports: bigint
  /** Length of the uptime window in seconds, from 1 to `MAX_DEAL_DURATION_SECONDS`; it starts at acceptance. */
  durationSeconds: bigint
}

/**
 * Builds the `create_deal` instruction, which proposes a deal and locks the payer's payment.
 *
 * @param params the deal parties, id, deposits and window length
 * @returns the instruction, with accounts in the program's order: payer, recipient, oracle, deal, system program
 * @throws RangeError when the duration is outside 1..`MAX_DEAL_DURATION_SECONDS`
 */
export function createDealInstruction(params: CreateDealParams): TransactionInstruction {
  if (params.durationSeconds < 1n || params.durationSeconds > MAX_DEAL_DURATION_SECONDS) {
    throw new RangeError(`The window must be 1 to ${MAX_DEAL_DURATION_SECONDS} seconds.`)
  }
  const data = new Uint8Array(40)
  data.set(CREATE_DEAL_DISCRIMINATOR, 0)
  data.set(u64le(params.dealId), 8)
  data.set(u64le(params.amountLamports), 16)
  data.set(u64le(params.guaranteeLamports), 24)
  data.set(u64le(params.durationSeconds), 32)
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

export interface AcceptDealParams {
  /** The uptime_deal program. */
  programId: PublicKey
  /** The deal's recipient; signs and pays the guarantee. */
  recipient: PublicKey
  /** The proposed deal. */
  deal: PublicKey
  /** The payer's payment the recipient agrees to. */
  amountLamports: bigint
  /** The guarantee the recipient agrees to lock. */
  guaranteeLamports: bigint
  /** The window length the recipient agrees to. */
  durationSeconds: bigint
  /** The oracle the recipient agrees to. */
  oracle: PublicKey
}

/**
 * Builds the `accept_deal` instruction, which locks the recipient's guarantee and starts the window. The
 * program rejects it unless the given terms equal the deal's, so the recipient only ever binds itself to
 * the terms it was shown.
 *
 * @param params the program, the recipient, the deal and the terms the recipient agrees to
 * @returns the instruction, with accounts in the program's order: recipient, deal, system program
 */
export function acceptDealInstruction(params: AcceptDealParams): TransactionInstruction {
  const data = new Uint8Array(64)
  data.set(ACCEPT_DEAL_DISCRIMINATOR, 0)
  data.set(u64le(params.amountLamports), 8)
  data.set(u64le(params.guaranteeLamports), 16)
  data.set(u64le(params.durationSeconds), 24)
  data.set(params.oracle.toBytes(), 32)
  return new TransactionInstruction({
    programId: params.programId,
    keys: [
      { pubkey: params.recipient, isSigner: true, isWritable: true },
      { pubkey: params.deal, isSigner: false, isWritable: true },
      { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
    ],
    data: Buffer.from(data),
  })
}

export interface CancelDealParams {
  /** The uptime_deal program. */
  programId: PublicKey
  /** The deal's payer or recipient; signs. */
  signer: PublicKey
  /** The deal account to close. */
  deal: PublicKey
  /** The deal's payer; receives its payment and the rent back. */
  payer: PublicKey
  /** The deal's recipient; receives its guarantee back if it had accepted. */
  recipient: PublicKey
}

/**
 * Builds the `cancel_deal` instruction, which returns each deposit to the party that paid it and closes the
 * deal: at any time for a proposal, and once the window plus `CANCEL_TIMEOUT_SECONDS` has passed on chain for
 * an accepted deal.
 *
 * @param params the program, the signing party, the deal and both parties
 * @returns the instruction, with accounts in the program's order: signer, deal, payer, recipient
 */
export function cancelDealInstruction(params: CancelDealParams): TransactionInstruction {
  return new TransactionInstruction({
    programId: params.programId,
    keys: [
      { pubkey: params.signer, isSigner: true, isWritable: false },
      { pubkey: params.deal, isSigner: false, isWritable: true },
      { pubkey: params.payer, isSigner: false, isWritable: true },
      { pubkey: params.recipient, isSigner: false, isWritable: true },
    ],
    data: Buffer.from(CANCEL_DEAL_DISCRIMINATOR),
  })
}
