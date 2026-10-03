import { PublicKey, Transaction, type Connection, type TransactionSignature } from '@solana/web3.js'
import { dealApi, type DealConfig, type TrackedDeal } from './dealApi'
import {
  acceptDealInstruction, cancelDealInstruction, createDealInstruction, dealAddress, MAX_DEAL_DURATION_SECONDS, MIN_DEAL_LAMPORTS,
} from './dealProgram'

/** The wallet adapter's `sendTransaction`: signs with the connected wallet and submits. */
export type SendTransaction = (transaction: Transaction, connection: Connection) => Promise<TransactionSignature>

export interface OpenDealParams {
  connection: Connection
  /** The connected wallet; it signs and pays its payment. */
  payer: PublicKey
  sendTransaction: SendTransaction
  config: DealConfig
  /** Base58 wallet of the provider, which must accept the deal. */
  recipient: string
  /** The payer's payment. */
  amountLamports: bigint
  /** The guarantee the provider locks when it accepts. */
  guaranteeLamports: bigint
  durationSeconds: number
  /** Overrides the deal id (defaults to the current time in ms, unique per payer). */
  dealId?: bigint
}

/**
 * Proposes a deal on chain with `create_deal`, which locks the payer's payment, waits for confirmation,
 * then registers it with the uptime service. The service follows the proposal until the provider accepts
 * it and settles it once the window has passed.
 *
 * @param params the wallet, the service configuration and the deal terms
 * @returns the deal as tracked by the service, PROPOSED
 * @throws Error for an invalid recipient, payment, guarantee or duration, a rejected or failed transaction,
 *   or a refused registration (the message then names the deal address and how to withdraw the proposal)
 */
export async function openDeal(params: OpenDealParams): Promise<TrackedDeal> {
  let recipient: PublicKey
  try { recipient = new PublicKey(params.recipient) } catch { throw new Error('Enter a valid provider address.') }
  if (recipient.equals(params.payer)) throw new Error('The provider must be another wallet.')
  if (params.amountLamports < MIN_DEAL_LAMPORTS) throw new Error('The payment must be at least 0.001 SOL.')
  if (params.guaranteeLamports < MIN_DEAL_LAMPORTS) throw new Error('The guarantee must be at least 0.001 SOL.')

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
    guaranteeLamports: params.guaranteeLamports,
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
      `The deal ${address} was proposed on chain but the uptime service refused it (${reason}). ` +
      'Your payment is locked; withdraw the proposal with cancel_deal.',
    )
  }
}

export interface AcceptDealParams {
  connection: Connection
  /** The connected wallet; it must be the deal's recipient, and it signs and pays the guarantee. */
  recipient: PublicKey
  sendTransaction: SendTransaction
  config: DealConfig
  /** The proposal as shown to the recipient; its terms are the ones the recipient agrees to. */
  deal: TrackedDeal
}

/**
 * Accepts a proposal with `accept_deal`, which locks the provider's guarantee and starts the window, and
 * waits for confirmation. The terms sent are the ones in `deal`, so the program refuses the acceptance if
 * the deal on chain says anything else.
 *
 * @param params the wallet, the service configuration and the proposal
 * @throws Error when the wallet isn't the deal's recipient, the deal isn't a proposal, the wallet rejects,
 *   or the program refuses (expired, different terms, not enough SOL)
 */
export async function acceptDeal(params: AcceptDealParams): Promise<void> {
  if (params.deal.status !== 'PROPOSED') throw new Error('Only a proposal can be accepted.')
  if (params.deal.recipient !== params.recipient.toBase58()) throw new Error('Only the provider named in the deal can accept it.')
  const transaction = new Transaction().add(acceptDealInstruction({
    programId: new PublicKey(params.config.programId),
    recipient: params.recipient,
    deal: new PublicKey(params.deal.address),
    amountLamports: BigInt(params.deal.amountLamports),
    guaranteeLamports: BigInt(params.deal.guaranteeLamports),
    durationSeconds: BigInt(params.deal.durationSeconds),
    oracle: new PublicKey(params.config.oracle),
  }))
  const signature = await params.sendTransaction(transaction, params.connection)
  await waitForConfirmation(params.connection, signature)
}

export interface CancelDealParams {
  connection: Connection
  /** The connected wallet; it must be the deal's payer or recipient. */
  signer: PublicKey
  sendTransaction: SendTransaction
  programId: PublicKey
  /** The deal to cancel; its payer and recipient get their deposits back. */
  deal: Pick<TrackedDeal, 'address' | 'payer' | 'recipient'>
}

/**
 * Sends `cancel_deal`, which returns each deposit to the party that paid it and closes the deal, and waits
 * for confirmation. The program accepts it at any time for a proposal (withdraw or reject), and 10 minutes
 * after the window ends for an accepted deal.
 *
 * @param params the wallet, the program and the deal
 * @throws Error when the wallet rejects, the program refuses (too early, not a party) or the transaction isn't confirmed
 */
export async function cancelDeal(params: CancelDealParams): Promise<void> {
  const transaction = new Transaction().add(cancelDealInstruction({
    programId: params.programId,
    signer: params.signer,
    deal: new PublicKey(params.deal.address),
    payer: new PublicKey(params.deal.payer),
    recipient: new PublicKey(params.deal.recipient),
  }))
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
