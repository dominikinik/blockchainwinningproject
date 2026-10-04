import { PublicKey, Transaction, type Connection, type TransactionSignature } from '@solana/web3.js'
import type { DealConfig } from './dealApi'
import {
  acceptDealInstruction, BPS_DENOMINATOR, cancelDealInstruction, closedBy, createDealInstruction, dealAddress, decodeDeal,
  MAX_DEAL_DURATION_SECONDS, MAX_ROUNDS, MIN_DEAL_LAMPORTS, settleDealInstruction, totalRounds,
  type DealOutcome, type OnChainDeal,
} from './dealProgram'

/** The wallet adapter's `sendTransaction`: signs with the connected wallet and submits. */
export type SendTransaction = (transaction: Transaction, connection: Connection) => Promise<TransactionSignature>

/** How many recent transactions of a closed deal are searched for its closing event. */
const HISTORY_LIMIT = 10

export interface OpenDealParams {
  connection: Connection
  /** The connected wallet; it signs and funds the customer payment. */
  payer: PublicKey
  sendTransaction: SendTransaction
  config: DealConfig
  /** Base58 provider wallet, paid the whole escrow when the SLA is met. */
  recipient: string
  amountLamports: bigint
  /** The provider guarantee the recipient must lock with `accept_deal`; 0 starts the window at once. */
  providerStakeLamports: bigint
  durationSeconds: number
  checkIntervalSeconds: number
  minUptimeBps: number
  /** Overrides the deal id (defaults to the current time in ms, unique per payer). */
  dealId?: bigint
}

/**
 * Locks the customer payment on chain with `create_deal` and waits for confirmation. The uptime monitor
 * finds the deal on chain by itself (it names the monitor's oracle key), so nothing is sent to it.
 *
 * @param params the wallet, the service configuration and the deal terms
 * @returns the Base58 deal address
 * @throws Error for an invalid recipient, amount, window, interval or threshold, or a rejected or failed
 *   transaction
 */
export async function openDeal(params: OpenDealParams): Promise<string> {
  let recipient: PublicKey
  try { recipient = new PublicKey(params.recipient) } catch { throw new Error('Enter a valid recipient address.') }
  if (recipient.equals(params.payer)) throw new Error('The recipient must be another wallet.')
  if (params.amountLamports < MIN_DEAL_LAMPORTS) throw new Error('The payment must be at least 0.001 SOL.')
  if (params.providerStakeLamports < 0n) throw new Error('The provider guarantee cannot be negative.')
  if (!Number.isInteger(params.durationSeconds) || params.durationSeconds < 1 || BigInt(params.durationSeconds) > MAX_DEAL_DURATION_SECONDS) {
    throw new Error(`The window must be a whole number of 1 to ${MAX_DEAL_DURATION_SECONDS} seconds.`)
  }
  if (totalRounds(params.durationSeconds, params.checkIntervalSeconds) === null) {
    throw new Error(`The check interval must divide the window into 1 to ${MAX_ROUNDS} rounds.`)
  }
  if (!Number.isInteger(params.minUptimeBps) || params.minUptimeBps < 1 || params.minUptimeBps > BPS_DENOMINATOR) {
    throw new Error('The minimum uptime must be between 0.01% and 100%.')
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
    providerStakeLamports: params.providerStakeLamports,
    durationSeconds: BigInt(params.durationSeconds),
    checkIntervalSeconds: BigInt(params.checkIntervalSeconds),
    minUptimeBps: params.minUptimeBps,
  }))
  const signature = await params.sendTransaction(transaction, params.connection)
  await waitForConfirmation(params.connection, signature)
  return dealAddress(programId, params.payer, dealId).toBase58()
}

interface DealActionParams {
  connection: Connection
  sendTransaction: SendTransaction
  programId: PublicKey
  /** The deal account. */
  deal: PublicKey
}

/**
 * Sends `accept_deal`: the connected wallet, which must be the deal's recipient, locks the provider guarantee
 * and the window starts.
 *
 * @param params the wallet (as `recipient`), the program and the deal address
 * @throws Error when the wallet rejects, the program refuses (not the recipient, already active) or the
 *   transaction isn't confirmed
 */
export async function acceptDeal(params: DealActionParams & { recipient: PublicKey }): Promise<void> {
  await sendAndConfirm(params, new Transaction().add(acceptDealInstruction({ programId: params.programId, recipient: params.recipient, deal: params.deal })))
}

/**
 * Sends `settle_deal`. Any wallet may call it once the window and the observation grace are over; the
 * program reads its own counters and pays the winner, so the caller only pays the fee.
 *
 * @param params the wallet (as `caller`), the program, the deal and its payer and recipient
 * @throws Error when the wallet rejects, the program refuses (too early, not active) or the transaction
 *   isn't confirmed
 */
export async function settleDeal(params: DealActionParams & { caller: PublicKey; payer: PublicKey; recipient: PublicKey }): Promise<void> {
  await sendAndConfirm(params, new Transaction().add(settleDealInstruction(params)))
}

/**
 * Sends `cancel_deal`, which withdraws a deal the provider hasn't accepted and refunds the payment.
 *
 * @param params the wallet (as `payer`), the program and the deal address
 * @throws Error when the wallet rejects, the program refuses (already active, not the payer) or the
 *   transaction isn't confirmed
 */
export async function cancelDeal(params: DealActionParams & { payer: PublicKey }): Promise<void> {
  await sendAndConfirm(params, new Transaction().add(cancelDealInstruction({ programId: params.programId, payer: params.payer, deal: params.deal })))
}

/**
 * Reads a deal's authoritative state straight from the chain.
 *
 * @param connection the cluster
 * @param programId the uptime_deal program
 * @param address the deal address
 * @returns the decoded deal, or null once the account is closed (settled or cancelled)
 * @throws Error when the account belongs to another program or isn't a deal
 */
export async function readDeal(connection: Connection, programId: PublicKey, address: PublicKey): Promise<OnChainDeal | null> {
  const account = await connection.getAccountInfo(address, 'confirmed')
  if (!account) return null
  if (!account.owner.equals(programId)) throw new Error(`${address.toBase58()} is not an uptime_deal account.`)
  return decodeDeal(account.data)
}

/**
 * Reads how a closed deal ended from the program's own event, searching the deal's recent transactions.
 *
 * @param connection the cluster
 * @param address the deal address
 * @returns the outcome (who won, the final counters) or null when no closing event is found
 */
export async function readOutcome(connection: Connection, address: PublicKey): Promise<{ outcome: DealOutcome; signature: string } | null> {
  const signatures = await connection.getSignaturesForAddress(address, { limit: HISTORY_LIMIT }, 'confirmed')
  for (const { signature } of signatures) {
    const tx = await connection.getTransaction(signature, { commitment: 'confirmed', maxSupportedTransactionVersion: 0 })
    const outcome = tx?.meta?.logMessages ? closedBy(tx.meta.logMessages, address.toBase58()) : null
    if (outcome) return { outcome, signature }
  }
  return null
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

async function sendAndConfirm(params: { connection: Connection; sendTransaction: SendTransaction }, transaction: Transaction): Promise<void> {
  const signature = await params.sendTransaction(transaction, params.connection)
  await waitForConfirmation(params.connection, signature)
}
