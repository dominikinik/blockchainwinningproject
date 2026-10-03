import { PublicKey, Transaction, type Connection, type TransactionSignature } from '@solana/web3.js'
import { dealApi, type DealConfig, type TrackedDeal } from './dealApi'
import { createDealInstruction, dealAddress, MIN_DEAL_LAMPORTS } from './dealProgram'

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
 * @throws Error for an invalid recipient or amount, a rejected or failed transaction, or a refused registration
 */
export async function openDeal(params: OpenDealParams): Promise<TrackedDeal> {
  let recipient: PublicKey
  try { recipient = new PublicKey(params.recipient) } catch { throw new Error('Enter a valid recipient address.') }
  if (recipient.equals(params.payer)) throw new Error('The recipient must be another wallet.')
  if (params.amountLamports < MIN_DEAL_LAMPORTS) throw new Error('The escrow must be at least 0.001 SOL.')

  const programId = new PublicKey(params.config.programId)
  const dealId = params.dealId ?? BigInt(Date.now())
  const transaction = new Transaction().add(createDealInstruction({
    programId,
    payer: params.payer,
    recipient,
    oracle: new PublicKey(params.config.oracle),
    dealId,
    amountLamports: params.amountLamports,
  }))
  const signature = await params.sendTransaction(transaction, params.connection)
  await waitForConfirmation(params.connection, signature)
  return dealApi.register(dealAddress(programId, params.payer, dealId).toBase58(), params.durationSeconds)
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
