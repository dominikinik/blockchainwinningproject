// @vitest-environment node
// PDA hashing rejects jsdom's cross-realm Uint8Array; these tests need no DOM.
import { Keypair, PublicKey, type Connection, type Transaction } from '@solana/web3.js'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cancelledLog, dealBytes, settledLog } from '../../test/dealFixtures'
import { dealAddress } from './dealProgram'
import { acceptDeal, cancelDeal, openDeal, readDeal, readOutcome, requestAirdrop, settleDeal, waitForConfirmation, type OpenDealParams } from './dealService'


const programId = 'EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r'
const programKey = new PublicKey(programId)
const config = { programId, oracle: Keypair.generate().publicKey.toBase58(), rpcUrl: 'http://rpc', checkIntervalSeconds: 1 }
const payer = Keypair.generate().publicKey
const recipient = Keypair.generate().publicKey.toBase58()

function connectionWith(...statuses: unknown[]) {
  const getSignatureStatuses = vi.fn()
  statuses.forEach((s) => getSignatureStatuses.mockResolvedValueOnce({ value: [s] }))
  return { getSignatureStatuses, requestAirdrop: vi.fn().mockResolvedValue('air') } as unknown as Connection & {
    getSignatureStatuses: ReturnType<typeof vi.fn>; requestAirdrop: ReturnType<typeof vi.fn>
  }
}

const confirmed = { confirmationStatus: 'confirmed', err: null }

function terms(overrides: Partial<OpenDealParams> = {}): OpenDealParams {
  return {
    connection: connectionWith(confirmed), payer, sendTransaction: vi.fn().mockResolvedValue('sig'), config, recipient,
    amountLamports: 500_000_000n, providerStakeLamports: 0n, durationSeconds: 10, checkIntervalSeconds: 1, minUptimeBps: 9_900,
    ...overrides,
  }
}

describe('waitForConfirmation', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  it('polls until the transaction is confirmed', async () => {
    const connection = connectionWith(null, { confirmationStatus: 'processed', err: null }, confirmed)
    const done = waitForConfirmation(connection, 'sig')
    await vi.advanceTimersByTimeAsync(800)
    await expect(done).resolves.toBeUndefined()
    expect(connection.getSignatureStatuses).toHaveBeenCalledTimes(3)
    expect(connection.getSignatureStatuses).toHaveBeenCalledWith(['sig'])
  })

  it('accepts finalized transactions', async () => {
    await expect(waitForConfirmation(connectionWith({ confirmationStatus: 'finalized', err: null }), 'sig')).resolves.toBeUndefined()
  })

  it('throws on a failed transaction', async () => {
    const connection = connectionWith({ confirmationStatus: 'confirmed', err: { InstructionError: [0, { Custom: 6000 }] } })
    await expect(waitForConfirmation(connection, 'sig')).rejects.toThrow('Transaction failed: {"InstructionError":[0,{"Custom":6000}]}')
  })

  it('times out', async () => {
    const connection = { getSignatureStatuses: vi.fn().mockResolvedValue({ value: [null] }) } as unknown as Connection
    const done = waitForConfirmation(connection, 'sig', 1000)
    const assertion = expect(done).rejects.toThrow('not confirmed in time')
    await vi.advanceTimersByTimeAsync(1200)
    await assertion
  })
})

describe('openDeal', () => {
  const fetchMock = vi.fn()
  beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock) })
  afterEach(() => { vi.unstubAllGlobals() })

  it('sends create_deal with every term, waits for it, then returns the deal address', async () => {
    const params = terms({ providerStakeLamports: 100_000_000n, durationSeconds: 30, checkIntervalSeconds: 3, minUptimeBps: 9_950, dealId: 5n })

    await expect(openDeal(params)).resolves.toBe(dealAddress(programKey, payer, 5n).toBase58())

    const sendTransaction = vi.mocked(params.sendTransaction)
    const tx: Transaction = sendTransaction.mock.calls[0][0]
    expect(sendTransaction.mock.calls[0][1]).toBe(params.connection)
    expect(tx.instructions).toHaveLength(1)
    const [ix] = tx.instructions
    expect(ix.programId.toBase58()).toBe(programId)
    expect(ix.keys[1].pubkey.toBase58()).toBe(recipient)
    expect(ix.keys[2].pubkey.toBase58()).toBe(config.oracle)
    expect(ix.data.readBigUInt64LE(16)).toBe(500_000_000n)
    expect(ix.data.readBigUInt64LE(24)).toBe(100_000_000n)
    expect(ix.data.readBigUInt64LE(32)).toBe(30n)
    expect(ix.data.readBigUInt64LE(40)).toBe(3n)
    expect(ix.data.readUInt16LE(48)).toBe(9_950)
    expect(fetchMock).not.toHaveBeenCalled() // the monitor finds the deal on chain; no call to /api/deals
  })

  it.each([
    ['an invalid recipient', { recipient: 'nope' }, 'Enter a valid recipient address.'],
    ['the payer as recipient', { recipient: payer.toBase58() }, 'The recipient must be another wallet.'],
    ['a payment below the minimum', { amountLamports: 999_999n }, 'The payment must be at least 0.001 SOL.'],
    ['a negative guarantee', { providerStakeLamports: -1n }, 'The provider guarantee cannot be negative.'],
    ['a zero window', { durationSeconds: 0 }, 'The window must be a whole number of 1 to 86400 seconds.'],
    ['a fractional window', { durationSeconds: 1.5 }, 'The window must be a whole number'],
    ['a too long window', { durationSeconds: 86_401 }, 'The window must be a whole number'],
    ['an interval that does not divide the window', { checkIntervalSeconds: 3 }, 'The check interval must divide the window'],
    ['a zero interval', { checkIntervalSeconds: 0 }, 'The check interval must divide the window'],
    ['a zero threshold', { minUptimeBps: 0 }, 'The minimum uptime must be between 0.01% and 100%.'],
    ['a threshold above 100%', { minUptimeBps: 10_001 }, 'The minimum uptime must be between'],
  ] as [string, Partial<OpenDealParams>, string][])('rejects %s before sending', async (_name, overrides, message) => {
    const params = terms(overrides)
    await expect(openDeal(params)).rejects.toThrow(message)
    expect(params.sendTransaction).not.toHaveBeenCalled()
  })

  it('does not return an address when the wallet rejects or the transaction fails', async () => {
    await expect(openDeal(terms({ sendTransaction: vi.fn().mockRejectedValue(new Error('User rejected the request.')) }))).rejects.toThrow('User rejected')
    await expect(openDeal(terms({ connection: connectionWith({ confirmationStatus: 'confirmed', err: 'boom' }) }))).rejects.toThrow('Transaction failed')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('deal actions', () => {
  const deal = Keypair.generate().publicKey
  const other = Keypair.generate().publicKey

  async function sent(action: (connection: Connection, sendTransaction: ReturnType<typeof vi.fn>) => Promise<void>) {
    const connection = connectionWith(confirmed)
    const sendTransaction = vi.fn().mockResolvedValue('sig')
    await expect(action(connection, sendTransaction)).resolves.toBeUndefined()
    expect(sendTransaction.mock.calls[0][1]).toBe(connection)
    expect(connection.getSignatureStatuses).toHaveBeenCalledWith(['sig'])
    const tx: Transaction = sendTransaction.mock.calls[0][0]
    expect(tx.instructions).toHaveLength(1)
    return tx.instructions[0]
  }

  it('accept_deal is signed by the recipient', async () => {
    const ix = await sent((connection, sendTransaction) => acceptDeal({ connection, sendTransaction, programId: programKey, deal, recipient: payer }))
    expect([...ix.data]).toEqual([76, 156, 34, 30, 129, 136, 76, 244])
    expect(ix.keys[0].pubkey.equals(payer)).toBe(true)
    expect(ix.keys[1].pubkey.equals(deal)).toBe(true)
  })

  it('settle_deal can be sent by any wallet and carries no figures', async () => {
    const ix = await sent((connection, sendTransaction) =>
      settleDeal({ connection, sendTransaction, programId: programKey, deal, caller: other, payer, recipient: new PublicKey(recipient) }))
    expect([...ix.data]).toEqual([28, 10, 168, 174, 203, 149, 134, 54])
    expect(ix.keys.map((k) => k.pubkey.toBase58())).toEqual([other.toBase58(), deal.toBase58(), payer.toBase58(), recipient])
  })

  it('cancel_deal is signed by the payer', async () => {
    const ix = await sent((connection, sendTransaction) => cancelDeal({ connection, sendTransaction, programId: programKey, deal, payer }))
    expect([...ix.data]).toEqual([158, 86, 193, 45, 168, 111, 48, 29])
    expect(ix.keys.map((k) => k.pubkey.toBase58())).toEqual([payer.toBase58(), deal.toBase58()])
  })

  it('surfaces a rejected wallet and a failed transaction', async () => {
    await expect(cancelDeal({ connection: connectionWith(), payer, sendTransaction: vi.fn().mockRejectedValue(new Error('User rejected the request.')), programId: programKey, deal }))
      .rejects.toThrow('User rejected')
    const failing = connectionWith({ confirmationStatus: 'confirmed', err: { InstructionError: [0, { Custom: 6014 }] } })
    await expect(settleDeal({ connection: failing, payer, sendTransaction: vi.fn().mockResolvedValue('sig'), programId: programKey, deal, caller: payer, recipient: other }))
      .rejects.toThrow('Transaction failed')
  })
})

describe('readDeal', () => {
  const address = Keypair.generate().publicKey
  const oracle = new PublicKey(config.oracle)

  it('decodes the deal account read from chain', async () => {
    const data = dealBytes({ payer, recipient: new PublicKey(recipient), oracle, up: 7, down: 2 })
    const getAccountInfo = vi.fn().mockResolvedValue({ owner: programKey, data: Buffer.from(data) })
    const deal = await readDeal({ getAccountInfo } as unknown as Connection, programKey, address)
    expect(getAccountInfo).toHaveBeenCalledWith(address, 'confirmed')
    expect(deal).toMatchObject({ payer: payer.toBase58(), upChecks: 7, downChecks: 2, totalRounds: 10, minUptimeBps: 9_900 })
  })

  it('returns null for a closed deal and rejects foreign accounts', async () => {
    expect(await readDeal({ getAccountInfo: vi.fn().mockResolvedValue(null) } as unknown as Connection, programKey, address)).toBeNull()
    const foreign = { getAccountInfo: vi.fn().mockResolvedValue({ owner: payer, data: Buffer.alloc(0) }) } as unknown as Connection
    await expect(readDeal(foreign, programKey, address)).rejects.toThrow('not an uptime_deal account')
  })
})

describe('readOutcome', () => {
  const deal = Keypair.generate().publicKey

  function history(logs: Record<string, string[] | null>) {
    return {
      getSignaturesForAddress: vi.fn().mockResolvedValue(Object.keys(logs).map((signature) => ({ signature }))),
      getTransaction: vi.fn(async (signature: string) => logs[signature] === null ? null : { meta: { logMessages: logs[signature] } }),
    } as unknown as Connection & { getSignaturesForAddress: ReturnType<typeof vi.fn> }
  }

  it('reads the program verdict from the closing transaction', async () => {
    const connection = history({ newer: ['Program log: unrelated'], gone: null, closing: [settledLog(deal, 9, 1, 10, true)] })
    await expect(readOutcome(connection, deal)).resolves.toEqual({
      signature: 'closing',
      outcome: { cancelled: false, paidToRecipient: true, upChecks: 9, downChecks: 1, totalRounds: 10, minUptimeBps: 9_900, payoutLamports: 500_000_000n },
    })
    expect(connection.getSignaturesForAddress).toHaveBeenCalledWith(deal, { limit: 10 }, 'confirmed')
  })

  it('recognises a cancellation and returns null without a closing event', async () => {
    await expect(readOutcome(history({ c: [cancelledLog(deal, payer)] }), deal)).resolves.toEqual({ signature: 'c', outcome: { cancelled: true } })
    await expect(readOutcome(history({ a: ['Program log: x'] }), deal)).resolves.toBeNull()
  })
})

describe('requestAirdrop', () => {
  it('requests lamports and waits for the airdrop', async () => {
    const connection = connectionWith(confirmed)
    await requestAirdrop(connection, payer, 2_000_000_000)
    expect(connection.requestAirdrop).toHaveBeenCalledWith(payer, 2_000_000_000)
    expect(connection.getSignatureStatuses).toHaveBeenCalledWith(['air'])
  })
})
