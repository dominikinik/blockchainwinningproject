// @vitest-environment node
// PDA hashing rejects jsdom's cross-realm Uint8Array; these tests need no DOM.
import { Keypair, PublicKey, SystemProgram } from '@solana/web3.js'
import { describe, expect, it } from 'vitest'
import {
  acceptDealInstruction, BPS_DENOMINATOR, cancelDealInstruction, closedBy, createDealInstruction, dealAddress, decodeDeal,
  MAX_DEAL_DURATION_SECONDS, MAX_ROUNDS, MIN_DEAL_LAMPORTS, OBSERVATION_GRACE_SECONDS, requiredUpRounds,
  settleDealInstruction, totalRounds, u64le, type CreateDealParams,
} from './dealProgram'
import { cancelledLog, dealBytes, settledLog } from '../../test/dealFixtures'

const programId = new PublicKey('EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r')
const key = () => Keypair.generate().publicKey

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
    const payer = key()
    const [expected] = PublicKey.findProgramAddressSync([Buffer.from('deal'), payer.toBuffer(), Buffer.from(u64le(7n))], programId)
    expect(dealAddress(programId, payer, 7n).equals(expected)).toBe(true)
    expect(dealAddress(programId, payer, 8n).equals(expected)).toBe(false)
  })
})

describe('totalRounds and requiredUpRounds', () => {
  it('splits a window into whole rounds like the program', () => {
    expect(totalRounds(60, 1)).toBe(60)
    expect(totalRounds(60, 6)).toBe(10)
    expect(totalRounds(MAX_ROUNDS, 1)).toBe(MAX_ROUNDS)
  })

  it.each([[60, 7], [60, 0], [60, 120], [MAX_ROUNDS + 1, 1], [10, 1.5], [0, 1]])('rejects %s s in %s s rounds', (duration, interval) => {
    expect(totalRounds(duration, interval)).toBeNull()
  })

  it('needs the fewest UP rounds that reach the threshold', () => {
    expect(requiredUpRounds(10, 9_900)).toBe(10)
    expect(requiredUpRounds(10, 9_000)).toBe(9)
    expect(requiredUpRounds(100, 9_900)).toBe(99)
    expect(requiredUpRounds(3, BPS_DENOMINATOR)).toBe(3)
  })
})

describe('createDealInstruction', () => {
  const terms = (overrides: Partial<CreateDealParams> = {}): CreateDealParams => ({
    programId, payer: key(), recipient: key(), oracle: key(), dealId: 42n, amountLamports: 500_000_000n,
    providerStakeLamports: 100_000_000n, durationSeconds: 30n, checkIntervalSeconds: 3n, minUptimeBps: 9_950, ...overrides,
  })

  it('lays out accounts and Borsh data like the IDL', () => {
    const params = terms()
    const ix = createDealInstruction(params)

    expect(ix.programId.equals(programId)).toBe(true)
    expect(ix.keys.map((k) => [k.pubkey.toBase58(), k.isSigner, k.isWritable])).toEqual([
      [params.payer.toBase58(), true, true],
      [params.recipient.toBase58(), false, false],
      [params.oracle.toBase58(), false, false],
      [dealAddress(programId, params.payer, 42n).toBase58(), false, true],
      [SystemProgram.programId.toBase58(), false, false],
    ])
    expect([...ix.data.subarray(0, 8)]).toEqual([198, 212, 144, 151, 97, 56, 149, 113])
    expect(ix.data.readBigUInt64LE(8)).toBe(42n)
    expect(ix.data.readBigUInt64LE(16)).toBe(500_000_000n)
    expect(ix.data.readBigUInt64LE(24)).toBe(100_000_000n)
    expect(ix.data.readBigUInt64LE(32)).toBe(30n)
    expect(ix.data.readBigUInt64LE(40)).toBe(3n)
    expect(ix.data.readUInt16LE(48)).toBe(9_950)
    expect(ix.data).toHaveLength(50)
  })

  it.each([0n, MAX_DEAL_DURATION_SECONDS + 1n, -1n])('rejects a duration of %s', (durationSeconds) => {
    expect(() => createDealInstruction(terms({ durationSeconds, checkIntervalSeconds: 1n }))).toThrow(RangeError)
  })

  it.each([0n, 7n, 31n])('rejects a check interval of %s', (checkIntervalSeconds) => {
    expect(() => createDealInstruction(terms({ checkIntervalSeconds }))).toThrow('check interval')
  })

  it.each([0, 10_001, 99.5])('rejects a threshold of %s bps', (minUptimeBps) => {
    expect(() => createDealInstruction(terms({ minUptimeBps }))).toThrow('minimum uptime')
  })

  it('accepts the bounds', () => {
    expect(createDealInstruction(terms({ durationSeconds: 1n, checkIntervalSeconds: 1n, minUptimeBps: 1 })).data.readUInt16LE(48)).toBe(1)
    const longest = createDealInstruction(terms({ durationSeconds: MAX_DEAL_DURATION_SECONDS, checkIntervalSeconds: 60n, minUptimeBps: 10_000 }))
    expect(longest.data.readBigUInt64LE(32)).toBe(MAX_DEAL_DURATION_SECONDS)
  })

  it('exposes the program limits', () => {
    expect(MIN_DEAL_LAMPORTS).toBe(1_000_000n)
    expect(MAX_DEAL_DURATION_SECONDS).toBe(86_400n)
    expect(MAX_ROUNDS).toBe(8_192)
    expect(OBSERVATION_GRACE_SECONDS).toBe(10)
  })
})

describe('acceptDealInstruction', () => {
  it('lays out recipient, deal and system program with the bare discriminator', () => {
    const [recipient, deal] = [key(), key()]
    const ix = acceptDealInstruction({ programId, recipient, deal })
    expect(ix.keys.map((k) => [k.pubkey.toBase58(), k.isSigner, k.isWritable])).toEqual([
      [recipient.toBase58(), true, true],
      [deal.toBase58(), false, true],
      [SystemProgram.programId.toBase58(), false, false],
    ])
    expect([...ix.data]).toEqual([76, 156, 34, 30, 129, 136, 76, 244])
  })
})

describe('settleDealInstruction', () => {
  it('carries no figures: only the discriminator, signed by any caller', () => {
    const [caller, deal, payer, recipient] = [key(), key(), key(), key()]
    const ix = settleDealInstruction({ programId, caller, deal, payer, recipient })
    expect(ix.keys.map((k) => [k.pubkey.toBase58(), k.isSigner, k.isWritable])).toEqual([
      [caller.toBase58(), true, false],
      [deal.toBase58(), false, true],
      [payer.toBase58(), false, true],
      [recipient.toBase58(), false, true],
    ])
    expect([...ix.data]).toEqual([28, 10, 168, 174, 203, 149, 134, 54])
  })
})

describe('cancelDealInstruction', () => {
  it('lays out payer and deal accounts with the bare discriminator', () => {
    const payer = key()
    const deal = dealAddress(programId, payer, 3n)
    const ix = cancelDealInstruction({ programId, payer, deal })
    expect(ix.keys.map((k) => [k.pubkey.toBase58(), k.isSigner, k.isWritable])).toEqual([
      [payer.toBase58(), true, true],
      [deal.toBase58(), false, true],
    ])
    expect([...ix.data]).toEqual([158, 86, 193, 45, 168, 111, 48, 29])
  })
})

describe('decodeDeal', () => {
  const [payer, recipient, oracle] = [key(), key(), key()]

  it('reads the terms and the on-chain counters', () => {
    const deal = decodeDeal(dealBytes({ payer, recipient, oracle, dealId: 9n, stake: 7n, up: 8, down: 1, recorded: [0xff, 0b10] }))
    expect(deal).toEqual({
      payer: payer.toBase58(), recipient: recipient.toBase58(), oracle: oracle.toBase58(), dealId: 9n, amountLamports: 500_000_000n,
      providerStakeLamports: 7n, acceptDeadline: 1_790_086_400, active: true, startsAt: 1_790_000_000, durationSeconds: 10, checkIntervalSeconds: 1,
      minUptimeBps: 9_900, totalRounds: 10, upChecks: 8, downChecks: 1, recorded: new Uint8Array([0xff, 0b10]),
    })
  })

  it('has no start while the deal waits for the provider', () => {
    expect(decodeDeal(dealBytes({ payer, recipient, oracle, active: false, startsAt: 0n })).startsAt).toBeNull()
  })

  it('rejects other and truncated accounts', () => {
    const data = dealBytes({ payer, recipient, oracle })
    const wrong = data.slice()
    wrong[0] ^= 1
    expect(() => decodeDeal(wrong)).toThrow('not an uptime_deal Deal')
    expect(() => decodeDeal(data.slice(0, 171))).toThrow('not an uptime_deal Deal')
    expect(() => decodeDeal(data.slice(0, 181))).toThrow('truncated')
  })
})

describe('closedBy', () => {
  const [deal, other, payer] = [key(), key(), key()]

  it('reads the verdict and the final counters of the given deal', () => {
    expect(closedBy(['Program log: Instruction: SettleDeal', settledLog(other, 1, 0, 1, false), settledLog(deal, 9, 1, 10, true)], deal.toBase58()))
      .toEqual({ cancelled: false, paidToRecipient: true, upChecks: 9, downChecks: 1, totalRounds: 10, minUptimeBps: 9_900, payoutLamports: 500_000_000n })
    expect(closedBy([settledLog(deal, 8, 2, 10, false)], deal.toBase58())).toMatchObject({ paidToRecipient: false })
  })

  it('recognises a cancellation', () => {
    expect(closedBy([cancelledLog(deal, payer)], deal.toBase58())).toEqual({ cancelled: true })
  })

  it('ignores other deals and unrelated lines', () => {
    expect(closedBy([cancelledLog(other, payer), settledLog(other, 1, 0, 1, true)], deal.toBase58())).toBeNull()
    expect(closedBy(['Program data: AAAA', 'Program log: hi'], deal.toBase58())).toBeNull()
  })
})
