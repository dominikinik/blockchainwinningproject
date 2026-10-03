import { AnchorProvider, BN, Program, Wallet, type Idl } from '@coral-xyz/anchor'
import { Connection, Keypair, PublicKey, SystemProgram, type TransactionInstruction } from '@solana/web3.js'
import { loadIdl } from './idl.js'
import type { ScheduleParams } from './schedule.js'

/** An SLA that lists this monitor, reduced to what the node needs. Addresses are base58. */
export interface AssignedSla extends ScheduleParams {
  address: string
  endpoint: string
  timeoutMs: number
  reportGraceSecs: number
  nextWindowToFinalize: number
  settled: boolean
  /** Monitor authorities, in `sla.monitors` order. */
  monitors: string[]
}

/** The seam between the node logic and the chain. */
export interface ProgramClient {
  fetchAssignedSlas(): Promise<AssignedSla[]>
  /** `WindowReport.payer`, or null when the account does not exist. */
  fetchWindowReportPayer(sla: string, windowIndex: number): Promise<string | null>
  submitReport(sla: string, windowIndex: number, checked: Uint8Array, up: Uint8Array): Promise<void>
  finalizeWindow(sla: string, windowIndex: number, payer: string, monitors: string[]): Promise<void>
}

/** `getProgramAccounts` memcmp offset of `monitors[i]` in a Sla account. */
export const monitorOffset = (i: number) => 92 + 32 * i
export const MAX_MONITORS = 5

export function monitorPda(programId: PublicKey, authority: PublicKey): PublicKey {
  return PublicKey.findProgramAddressSync([Buffer.from('monitor'), authority.toBuffer()], programId)[0]
}

export function windowReportPda(programId: PublicKey, sla: PublicKey, windowIndex: number): PublicKey {
  const idx = Buffer.alloc(4)
  idx.writeUInt32LE(windowIndex)
  return PublicKey.findProgramAddressSync([Buffer.from('window'), sla.toBuffer(), idx], programId)[0]
}

/** Thin adapter over the Anchor program. Contains no scheduling or retry logic. */
export class AnchorProgramClient implements ProgramClient {
  readonly program: Program
  private programId: PublicKey

  constructor(
    connection: Connection,
    private keypair: Keypair,
    programId?: PublicKey,
  ) {
    const idl = { ...loadIdl(), address: (programId ?? new PublicKey(loadIdl().address)).toBase58() }
    this.program = new Program(idl as unknown as Idl, new AnchorProvider(connection, new Wallet(keypair), {}))
    this.programId = this.program.programId
  }

  async fetchAssignedSlas(): Promise<AssignedSla[]> {
    const me = this.keypair.publicKey.toBase58()
    const results = await Promise.all(
      Array.from({ length: MAX_MONITORS }, (_, i) =>
        (this.program.account as any).sla.all([{ memcmp: { offset: monitorOffset(i), bytes: me } }]),
      ),
    )
    const seen = new Map<string, AssignedSla>()
    for (const { publicKey, account: a } of results.flat() as { publicKey: PublicKey; account: any }[]) {
      const address = publicKey.toBase58()
      if (seen.has(address)) continue
      seen.set(address, {
        address,
        endpoint: a.endpoint,
        timeoutMs: a.timeoutMs,
        startTs: toNum(a.startTs),
        endTs: toNum(a.endTs),
        checkIntervalSecs: a.checkIntervalSecs,
        windowSecs: a.windowSecs,
        reportGraceSecs: a.reportGraceSecs,
        totalWindows: a.totalWindows,
        nextWindowToFinalize: a.nextWindowToFinalize,
        settled: a.settled,
        monitors: a.monitors.map((m: PublicKey) => m.toBase58()),
      })
    }
    return [...seen.values()]
  }

  async fetchWindowReportPayer(sla: string, windowIndex: number): Promise<string | null> {
    const pda = windowReportPda(this.programId, new PublicKey(sla), windowIndex)
    const acct = await (this.program.account as any).windowReport.fetchNullable(pda)
    return acct ? (acct.payer as PublicKey).toBase58() : null
  }

  submitReportBuilder(sla: string, windowIndex: number, checked: Uint8Array, up: Uint8Array) {
    const slaKey = new PublicKey(sla)
    return this.program.methods
      .submitReport(windowIndex, Array.from(checked), Array.from(up))
      .accountsPartial({
        monitorAuthority: this.keypair.publicKey,
        monitor: monitorPda(this.programId, this.keypair.publicKey),
        sla: slaKey,
        windowReport: windowReportPda(this.programId, slaKey, windowIndex),
        systemProgram: SystemProgram.programId,
      })
  }

  finalizeWindowBuilder(sla: string, windowIndex: number, payer: string, monitors: string[]) {
    const slaKey = new PublicKey(sla)
    return this.program.methods
      .finalizeWindow(windowIndex)
      .accountsPartial({
        sla: slaKey,
        windowReport: windowReportPda(this.programId, slaKey, windowIndex),
        payer: new PublicKey(payer),
      })
      .remainingAccounts(
        monitors.map((m) => ({
          pubkey: monitorPda(this.programId, new PublicKey(m)),
          isWritable: true,
          isSigner: false,
        })),
      )
  }

  buildSubmitReportIx(...args: Parameters<AnchorProgramClient['submitReportBuilder']>): Promise<TransactionInstruction> {
    return this.submitReportBuilder(...args).instruction()
  }

  buildFinalizeWindowIx(...args: Parameters<AnchorProgramClient['finalizeWindowBuilder']>): Promise<TransactionInstruction> {
    return this.finalizeWindowBuilder(...args).instruction()
  }

  async submitReport(sla: string, windowIndex: number, checked: Uint8Array, up: Uint8Array): Promise<void> {
    await this.submitReportBuilder(sla, windowIndex, checked, up).rpc()
  }

  async finalizeWindow(sla: string, windowIndex: number, payer: string, monitors: string[]): Promise<void> {
    // The payer is only a writable account unless it is the wallet itself; the wallet signs as fee payer.
    await this.finalizeWindowBuilder(sla, windowIndex, payer, monitors).rpc()
  }
}

function toNum(v: BN | number): number {
  return typeof v === 'number' ? v : v.toNumber()
}
