// @vitest-environment node
// PDA hashing rejects jsdom's cross-realm Uint8Array; these tests need no DOM.
import { Keypair, PublicKey, SystemProgram } from '@solana/web3.js'
import { describe, expect, it } from 'vitest'
import { cancelDealInstruction, CANCEL_TIMEOUT_SECONDS, createDealInstruction, dealAddress, MAX_DEAL_DURATION_SECONDS, MIN_DEAL_LAMPORTS, u64le } from './dealProgram'

const programId = new PublicKey('EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r')

describe('u64le', () => {
  it('encodes little-endian u64 values', () => {
    expect([...u64le(0n)]).toEqual([0, 0, 0, 0, 0, 0, 0, 0])
    expect([...u64le(258n)]).toEqual([2, 1, 0, 0, 0, 0, 0, 0])
    expect([...u64le((1n << 64n) - 1n)]).toEqual([255, 255, 255, 255, 255, 255, 255, 255])
  })

  it('rejects values outside u64', () => {
    expect(() => u64le(-1n)).toThrow(RangeError)
    expect(() => u64le(1n << 64n)).toThrow(RangeError)
  })
})

describe('dealAddress', () => {
  it('derives the PDA from "deal", the payer and the little-endian id', () => {
    const payer = Keypair.generate().publicKey
    const [expected] = PublicKey.findProgramAddressSync([Buffer.from('deal'), payer.toBuffer(), Buffer.from(u64le(7n))], programId)
    expect(dealAddress(programId, payer, 7n).equals(expected)).toBe(true)
    expect(dealAddress(programId, payer, 8n).equals(expected)).toBe(false)
  })
})

describe('createDealInstruction', () => {
  it('lays out accounts and Borsh data like the IDL', () => {
    const [payer, recipient, oracle] = [Keypair.generate().publicKey, Keypair.generate().publicKey, Keypair.generate().publicKey]
    const ix = createDealInstruction({ programId, payer, recipient, oracle, dealId: 42n, amountLamports: 500_000_000n, durationSeconds: 30n })

    expect(ix.programId.equals(programId)).toBe(true)
    expect(ix.keys.map((k) => [k.pubkey.toBase58(), k.isSigner, k.isWritable])).toEqual([
      [payer.toBase58(), true, true],
      [recipient.toBase58(), false, false],
      [oracle.toBase58(), false, false],
      [dealAddress(programId, payer, 42n).toBase58(), false, true],
      [SystemProgram.programId.toBase58(), false, false],
    ])
    expect([...ix.data.subarray(0, 8)]).toEqual([198, 212, 144, 151, 97, 56, 149, 113])
    expect(ix.data.readBigUInt64LE(8)).toBe(42n)
    expect(ix.data.readBigUInt64LE(16)).toBe(500_000_000n)
    expect(ix.data.readBigUInt64LE(24)).toBe(30n)
    expect(ix.data).toHaveLength(32)
  })

  it.each([0n, MAX_DEAL_DURATION_SECONDS + 1n, -1n])('rejects a duration of %s', (durationSeconds) => {
    const [payer, recipient, oracle] = [Keypair.generate().publicKey, Keypair.generate().publicKey, Keypair.generate().publicKey]
    expect(() => createDealInstruction({ programId, payer, recipient, oracle, dealId: 1n, amountLamports: 1_000_000n, durationSeconds })).toThrow(RangeError)
  })

  it('accepts the duration bounds', () => {
    const [payer, recipient, oracle] = [Keypair.generate().publicKey, Keypair.generate().publicKey, Keypair.generate().publicKey]
    for (const durationSeconds of [1n, MAX_DEAL_DURATION_SECONDS]) {
      const ix = createDealInstruction({ programId, payer, recipient, oracle, dealId: 1n, amountLamports: 1_000_000n, durationSeconds })
      expect(ix.data.readBigUInt64LE(24)).toBe(durationSeconds)
    }
  })

  it('exposes the program limits', () => {
    expect(MIN_DEAL_LAMPORTS).toBe(1_000_000n)
    expect(MAX_DEAL_DURATION_SECONDS).toBe(86_400n)
    expect(CANCEL_TIMEOUT_SECONDS).toBe(600)
  })
})

describe('cancelDealInstruction', () => {
  it('lays out payer and deal accounts with the bare discriminator', () => {
    const payer = Keypair.generate().publicKey
    const deal = dealAddress(programId, payer, 3n)
    const ix = cancelDealInstruction({ programId, payer, deal })
    expect(ix.programId.equals(programId)).toBe(true)
    expect(ix.keys.map((k) => [k.pubkey.toBase58(), k.isSigner, k.isWritable])).toEqual([
      [payer.toBase58(), true, true],
      [deal.toBase58(), false, true],
    ])
    expect([...ix.data]).toEqual([158, 86, 193, 45, 168, 111, 48, 29])
  })
})
