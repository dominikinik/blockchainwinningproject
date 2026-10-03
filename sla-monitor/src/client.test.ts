import { Connection, Keypair, PublicKey, SystemProgram } from '@solana/web3.js'
import { BorshInstructionCoder, type Idl } from '@coral-xyz/anchor'
import { describe, expect, it } from 'vitest'
import { AnchorProgramClient, MAX_MONITORS, monitorOffset, monitorPda, windowReportPda } from './client.js'
import { loadIdl } from './idl.js'

const idl = loadIdl()
const programId = new PublicKey(idl.address)
const keypair = Keypair.generate()
// Never connects: only .instruction() and PDA derivation are used.
const client = new AnchorProgramClient(new Connection('http://127.0.0.1:1'), keypair, programId)
const sla = Keypair.generate().publicKey
const others = [Keypair.generate().publicKey, keypair.publicKey, Keypair.generate().publicKey]
const disc = (name: string) =>
  Buffer.from((idl.instructions as unknown as { name: string; discriminator: number[] }[]).find((i) => i.name === name)!.discriminator)

describe('AnchorProgramClient instructions', () => {
  it('builds submit_report with discriminator, accounts and args', async () => {
    const checked = new Uint8Array(32)
    const up = new Uint8Array(32)
    checked[0] = 0b1111
    up[0] = 0b0101
    checked[31] = 0x80
    const ix = await client.buildSubmitReportIx(sla.toBase58(), 7, checked, up)
    expect(ix.programId.equals(programId)).toBe(true)
    expect(ix.data.subarray(0, 8)).toEqual(disc('submit_report'))
    expect(ix.keys.map((k) => [k.pubkey.toBase58(), k.isSigner, k.isWritable])).toEqual([
      [keypair.publicKey.toBase58(), true, true],
      [monitorPda(programId, keypair.publicKey).toBase58(), false, true],
      [sla.toBase58(), false, false],
      [windowReportPda(programId, sla, 7).toBase58(), false, true],
      [SystemProgram.programId.toBase58(), false, false],
    ])
    // args: u32 LE window, [u8;32] checked, [u8;32] up
    const args = ix.data.subarray(8)
    expect(args).toHaveLength(4 + 32 + 32)
    expect(args.readUInt32LE(0)).toBe(7)
    expect([...args.subarray(4, 36)]).toEqual([...checked])
    expect([...args.subarray(36)]).toEqual([...up])
    const decoded = new BorshInstructionCoder(idl as unknown as Idl).decode(ix.data)
    expect(decoded?.name).toBe('submit_report')
  })

  it('builds finalize_window with remaining monitor PDAs in order, writable', async () => {
    const payer = Keypair.generate().publicKey
    const ix = await client.buildFinalizeWindowIx(
      sla.toBase58(),
      300,
      payer.toBase58(),
      others.map((o) => o.toBase58()),
    )
    expect(ix.data.subarray(0, 8)).toEqual(disc('finalize_window'))
    expect(ix.data.readUInt32LE(8)).toBe(300)
    expect(ix.data).toHaveLength(12)
    expect(ix.keys.map((k) => [k.pubkey.toBase58(), k.isSigner, k.isWritable])).toEqual([
      [sla.toBase58(), false, true],
      [windowReportPda(programId, sla, 300).toBase58(), false, true],
      [payer.toBase58(), false, true],
      ...others.map((o) => [monitorPda(programId, o).toBase58(), false, true]),
    ])
  })
})

describe('PDA and memcmp helpers', () => {
  it('derives the window PDA from the u32 LE index', () => {
    const idx = Buffer.from([1, 2, 0, 0])
    const [expected] = PublicKey.findProgramAddressSync([Buffer.from('window'), sla.toBuffer(), idx], programId)
    expect(windowReportPda(programId, sla, 0x201).equals(expected)).toBe(true)
  })
  it('uses memcmp offsets 92 + 32*i for up to 5 positions', () => {
    expect(Array.from({ length: MAX_MONITORS }, (_, i) => monitorOffset(i))).toEqual([92, 124, 156, 188, 220])
  })
})

describe('fetchAssignedSlas', () => {
  it('runs 5 memcmp queries and dedupes results', async () => {
    const calls: { offset: number; bytes: string }[] = []
    const acct = (n: number) => ({
      publicKey: new PublicKey(new Uint8Array(32).fill(n)),
      account: {
        endpoint: 'https://e', timeoutMs: 1000, startTs: { toNumber: () => 1000 }, endTs: { toNumber: () => 2000 },
        checkIntervalSecs: 10, windowSecs: 100, reportGraceSecs: 20, totalWindows: 10, nextWindowToFinalize: 0,
        settled: false, monitors: [keypair.publicKey],
      },
    })
    ;(client.program.account as any).sla.all = async (f: { memcmp: { offset: number; bytes: string } }[]) => {
      calls.push(f[0]!.memcmp)
      return calls.length <= 2 ? [acct(1)] : [acct(2)]
    }
    const slas = await client.fetchAssignedSlas()
    expect(calls.map((c) => c.offset)).toEqual([92, 124, 156, 188, 220])
    expect(calls.every((c) => c.bytes === keypair.publicKey.toBase58())).toBe(true)
    expect(slas.map((s) => s.startTs)).toEqual([1000, 1000])
    expect(slas).toHaveLength(2)
    expect(slas[0]).toMatchObject({ endTs: 2000, monitors: [keypair.publicKey.toBase58()] })
  })
})
