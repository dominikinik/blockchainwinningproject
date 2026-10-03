// @vitest-environment node
// PDA hashing rejects jsdom's cross-realm Uint8Array; these tests need no DOM.
import { Keypair, PublicKey, type Connection, type Transaction } from '@solana/web3.js'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { dealApi } from './dealApi'
import { dealAddress } from './dealProgram'
import { cancelDeal, openDeal, requestAirdrop, waitForConfirmation } from './dealService'

vi.mock('./dealApi', () => ({ dealApi: { register: vi.fn() } }))

const programId = 'EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r'
const config = { programId, oracle: Keypair.generate().publicKey.toBase58(), rpcUrl: 'http://rpc' }
const payer = Keypair.generate().publicKey
const recipient = Keypair.generate().publicKey.toBase58()

function connectionWith(...statuses: unknown[]) {
  const getSignatureStatuses = vi.fn()
  statuses.forEach((s) => getSignatureStatuses.mockResolvedValueOnce({ value: [s] }))
  return { getSignatureStatuses, requestAirdrop: vi.fn().mockResolvedValue('air') } as unknown as Connection & {
    getSignatureStatuses: ReturnType<typeof vi.fn>; requestAirdrop: ReturnType<typeof vi.fn>
  }
}

describe('waitForConfirmation', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  it('polls until the transaction is confirmed', async () => {
    const connection = connectionWith(null, { confirmationStatus: 'processed', err: null }, { confirmationStatus: 'confirmed', err: null })
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
  beforeEach(() => { vi.mocked(dealApi.register).mockReset() })

  it('sends create_deal, waits for it, then registers the deal address', async () => {
    const connection = connectionWith({ confirmationStatus: 'confirmed', err: null })
    const sendTransaction = vi.fn().mockResolvedValue('sig')
    vi.mocked(dealApi.register).mockResolvedValue({ address: 'D' } as never)

    await expect(openDeal({ connection, payer, sendTransaction, config, recipient, amountLamports: 500_000_000n, durationSeconds: 10, dealId: 5n }))
      .resolves.toEqual({ address: 'D' })

    const tx: Transaction = sendTransaction.mock.calls[0][0]
    expect(sendTransaction.mock.calls[0][1]).toBe(connection)
    expect(tx.instructions).toHaveLength(1)
    expect(tx.instructions[0].programId.toBase58()).toBe(programId)
    expect(tx.instructions[0].keys[1].pubkey.toBase58()).toBe(recipient)
    expect(tx.instructions[0].keys[2].pubkey.toBase58()).toBe(config.oracle)
    expect(tx.instructions[0].data.readBigUInt64LE(16)).toBe(500_000_000n)
    expect(dealApi.register).toHaveBeenCalledWith(dealAddress(tx.instructions[0].programId, payer, 5n).toBase58())
    expect(tx.instructions[0].data.readBigUInt64LE(24)).toBe(10n)
    expect(tx.instructions[0].data).toHaveLength(32)
  })

  it.each([
    ['an invalid recipient', 'nope', 1_000_000n, 'Enter a valid recipient address.'],
    ['the payer as recipient', payer.toBase58(), 1_000_000n, 'The recipient must be another wallet.'],
    ['an amount below the minimum', recipient, 999_999n, 'The escrow must be at least 0.001 SOL.'],
  ])('rejects %s before sending', async (_name, to, amountLamports, message) => {
    const sendTransaction = vi.fn()
    await expect(openDeal({ connection: connectionWith(), payer, sendTransaction, config, recipient: to, amountLamports, durationSeconds: 10 }))
      .rejects.toThrow(message)
    expect(sendTransaction).not.toHaveBeenCalled()
  })

  it.each([0, -5, 1.5, 86_401, Number.NaN])('rejects a window of %s before sending', async (durationSeconds) => {
    const sendTransaction = vi.fn()
    await expect(openDeal({ connection: connectionWith(), payer, sendTransaction, config, recipient, amountLamports: 1_000_000n, durationSeconds }))
      .rejects.toThrow('The window must be a whole number of 1 to 86400 seconds.')
    expect(sendTransaction).not.toHaveBeenCalled()
  })

  it('names the deal address and the cancel_deal fallback when registration fails', async () => {
    const connection = connectionWith({ confirmationStatus: 'confirmed', err: null })
    vi.mocked(dealApi.register).mockRejectedValue(new Error('Deal names oracle X'))
    const error = await openDeal({ connection, payer, sendTransaction: vi.fn().mockResolvedValue('sig'), config, recipient, amountLamports: 1_000_000n, durationSeconds: 10, dealId: 9n })
      .catch((e: Error) => e)
    expect(error).toBeInstanceOf(Error)
    const address = dealAddress(new PublicKey(programId), payer, 9n).toBase58()
    expect((error as Error).message).toContain(address)
    expect((error as Error).message).toContain('Deal names oracle X')
    expect((error as Error).message).toContain('cancel_deal 10 minutes after the window ends')
  })

  it('does not register when the wallet rejects or the transaction fails', async () => {
    const rejected = vi.fn().mockRejectedValue(new Error('User rejected the request.'))
    await expect(openDeal({ connection: connectionWith(), payer, sendTransaction: rejected, config, recipient, amountLamports: 1_000_000n, durationSeconds: 10 }))
      .rejects.toThrow('User rejected')
    const failing = connectionWith({ confirmationStatus: 'confirmed', err: 'boom' })
    await expect(openDeal({ connection: failing, payer, sendTransaction: vi.fn().mockResolvedValue('sig'), config, recipient, amountLamports: 1_000_000n, durationSeconds: 10 }))
      .rejects.toThrow('Transaction failed')
    expect(dealApi.register).not.toHaveBeenCalled()
  })
})

describe('requestAirdrop', () => {
  it('requests lamports and waits for the airdrop', async () => {
    const connection = connectionWith({ confirmationStatus: 'confirmed', err: null })
    await requestAirdrop(connection, payer, 2_000_000_000)
    expect(connection.requestAirdrop).toHaveBeenCalledWith(payer, 2_000_000_000)
    expect(connection.getSignatureStatuses).toHaveBeenCalledWith(['air'])
  })
})

describe('cancelDeal', () => {
  const deal = Keypair.generate().publicKey

  it('sends cancel_deal signed by the payer and waits for confirmation', async () => {
    const connection = connectionWith({ confirmationStatus: 'confirmed', err: null })
    const sendTransaction = vi.fn().mockResolvedValue('sig')
    await expect(cancelDeal({ connection, payer, sendTransaction, programId: new PublicKey(programId), deal })).resolves.toBeUndefined()
    const tx: Transaction = sendTransaction.mock.calls[0][0]
    expect(sendTransaction.mock.calls[0][1]).toBe(connection)
    expect(tx.instructions).toHaveLength(1)
    expect([...tx.instructions[0].data]).toEqual([158, 86, 193, 45, 168, 111, 48, 29])
    expect(tx.instructions[0].keys.map((k) => k.pubkey.toBase58())).toEqual([payer.toBase58(), deal.toBase58()])
    expect(connection.getSignatureStatuses).toHaveBeenCalledWith(['sig'])
  })

  it('surfaces a rejected wallet and a failed transaction', async () => {
    const programKey = new PublicKey(programId)
    await expect(cancelDeal({ connection: connectionWith(), payer, sendTransaction: vi.fn().mockRejectedValue(new Error('User rejected the request.')), programId: programKey, deal }))
      .rejects.toThrow('User rejected')
    const failing = connectionWith({ confirmationStatus: 'confirmed', err: { InstructionError: [0, { Custom: 6005 }] } })
    await expect(cancelDeal({ connection: failing, payer, sendTransaction: vi.fn().mockResolvedValue('sig'), programId: programKey, deal }))
      .rejects.toThrow('Transaction failed')
  })
})
