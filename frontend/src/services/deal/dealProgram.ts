import { PublicKey, SystemProgram, TransactionInstruction } from '@solana/web3.js'
import { Buffer } from 'buffer'

/** Smallest escrow `create_deal` accepts (the program's `MIN_DEAL_LAMPORTS`). */
export const MIN_DEAL_LAMPORTS = 1_000_000n

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
}

/**
 * Builds the `create_deal` instruction, which locks the escrow in a new deal account.
 *
 * @param params the deal parties, id and amount
 * @returns the instruction, with accounts in the program's order: payer, recipient, oracle, deal, system program
 */
export function createDealInstruction(params: CreateDealParams): TransactionInstruction {
  const data = new Uint8Array(24)
  data.set(CREATE_DEAL_DISCRIMINATOR, 0)
  data.set(u64le(params.dealId), 8)
  data.set(u64le(params.amountLamports), 16)
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
