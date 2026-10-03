import { PublicKey, Transaction, type Connection, type TransactionSignature } from '@solana/web3.js'
import { dealApi, type DealConfig, type TrackedDeal } from './dealApi'
import { cancelDealInstruction, createDealInstruction, dealAddress, MAX_DEAL_DURATION_SECONDS, MIN_DEAL_LAMPORTS } from './dealProgram'

/** The wallet adapter's `sendTransaction`: signs with the connected wallet and submits. */
export type SendTransaction = (transaction: Transaction, connection: Connection) => Promise<TransactionSignature>

export interface OpenDealParams {
  connection: Connection
  /** The connected wallet; it signs and funds the escrow. */
  payer: PublicKey
  sendTransaction: SendTransaction
  config: DealConfig
  /** Base58 wallet paid when uptime is above 99%. */
  recipient: string
  amountLamports: bigint
  durationSeconds: number
  /** Overrides the deal id (defaults to the current time in ms, unique per payer). */
  dealId?: bigint
}

/**
 * Locks the escrow on chain with `create_deal`, waits for confirmation, then registers the deal with
 * the uptime service, which settles it once the window has passed.
 *
 * @param params the wallet, the service configuration and the deal terms
 * @returns the deal as tracked by the service
 * @throws Error for an invalid recipient, amount or duration, a rejected or failed transaction, or a refused
 *   registration (the message then names the deal address and how to reclaim the escrow)
 */
export async function openDeal(params: OpenDealParams): Promise<TrackedDeal> {
  let recipient: PublicKey
  try { recipient = new PublicKey(params.recipient) } catch { throw new Error('Enter a valid recipient address.') }
  if (recipient.equals(params.payer)) throw new Error('The recipient must be another wallet.')
  if (params.amountLamports < MIN_DEAL_LAMPORTS) throw new Error('The escrow must be at least 0.001 SOL.')

  if (!Number.isInteger(params.durationSeconds) || params.durationSeconds < 1 || BigInt(params.durationSeconds) > MAX_DEAL_DURATION_SECONDS) {
    throw new Error(`The window must be a whole number of 1 to ${MAX_DEAL_DURATION_SECONDS} seconds.`)
  }

  const programId = new PublicKey(params.config.programId)
  const dealId = params.dealId ?? BigInt(Date.now())
  const transaction = new Transaction().add(createDealInstruction({
    programId,
    payer: params.payer,
    recipient,
    oracle: new PublicKey(params.config.oracle),
    dealId,
    amountLamports: params.amountLamports,
    durationSeconds: BigInt(params.durationSeconds),
  }))
  const signature = await params.sendTransaction(transaction, params.connection)
  await waitForConfirmation(params.connection, signature)
  const address = dealAddress(programId, params.payer, dealId).toBase58()
  try {
    return await dealApi.register(address)
  } catch (cause) {
    const reason = cause instanceof Error ? cause.message : 'unknown error'
    throw new Error(
      `The deal ${address} was created on chain but the uptime service refused it (${reason}). ` +
      'Your escrow is locked; reclaim it with cancel_deal 10 minutes after the window ends.',
    )
  }
}

export interface CancelDealParams {
  connection: Connection
  /** The connected wallet; it must be the deal's payer. */
  payer: PublicKey
  sendTransaction: SendTransaction
  programId: PublicKey
  /** The deal account to cancel. */
  deal: PublicKey
}

/**
 * Sends `cancel_deal`, which refunds the escrow and closes the deal, and waits for confirmation.
 * The program only accepts it 10 minutes after the window ends.
 *
 * @param params the wallet, the program and the deal address
 * @throws Error when the wallet rejects, the program refuses (too early, not the payer) or the transaction isn't confirmed
 */
export async function cancelDeal(params: CancelDealParams): Promise<void> {
  const transaction = new Transaction().add(cancelDealInstruction({ programId: params.programId, payer: params.payer, deal: params.deal }))
  const signature = await params.sendTransaction(transaction, params.connection)
  await waitForConfirmation(params.connection, signature)
}

/**
 * Asks the cluster faucet for SOL (localnet and devnet) and waits until it lands.
 *
 * @param connection the cluster
 * @param address the wallet to fund
 * @param lamports how much to request
 * @throws Error when the faucet refuses or the airdrop isn't confirmed in time
 */
export async function requestAirdrop(connection: Connection, address: PublicKey, lamports: number): Promise<void> {
  await waitForConfirmation(connection, await connection.requestAirdrop(address, lamports))
}

/**
 * Polls a transaction until it is confirmed. Polling avoids the websocket subscription that
 * `confirmTransaction` needs, so it works through plain HTTP RPC.
 *
 * @param connection the cluster
 * @param signature the transaction signature
 * @param timeoutMs how long to wait
 * @throws Error when the transaction failed or was not confirmed within `timeoutMs`
 */
export async function waitForConfirmation(connection: Connection, signature: string, timeoutMs = 30_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const status = (await connection.getSignatureStatuses([signature])).value[0]
    if (status?.err) throw new Error(`Transaction failed: ${JSON.stringify(status.err)}`)
    if (status?.confirmationStatus === 'confirmed' || status?.confirmationStatus === 'finalized') return
    await new Promise((resolve) => setTimeout(resolve, 400))
  }
  throw new Error('The transaction was not confirmed in time.')
}
