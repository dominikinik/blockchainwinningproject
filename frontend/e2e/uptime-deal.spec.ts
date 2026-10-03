import { expect, test, type Page } from '@playwright/test'
import { Connection, Keypair, LAMPORTS_PER_SOL, PublicKey } from '@solana/web3.js'
import { BACKEND_URL, RPC_URL } from './env'

const connection = new Connection(RPC_URL, 'confirmed')
const ESCROW_SOL = 0.5
/** Window plus the program's 10 s observation grace plus the service's settle grace and confirmations. */
const SETTLE_TIMEOUT_MS = 60_000

/** Opens the deal page, connects a fresh burner wallet and funds it from the validator faucet. */
async function connectFundedWallet(page: Page): Promise<string> {
  await page.goto('/deal')
  await page.getByRole('button', { name: 'Select Wallet' }).first().click()
  await page.getByRole('button', { name: /Burner Wallet/ }).click()
  await page.getByRole('button', { name: 'Airdrop 2 SOL' }).click()
  await expect(page.getByTestId('wallet-balance')).toHaveText('2 SOL', { timeout: 20_000 })
  await expect(page.getByTestId('service-state')).toHaveText('UP')
  return (await page.getByText(/^Payer$/).locator('xpath=following-sibling::strong').getAttribute('title'))!
}

/**
 * Creates a 10-second deal of ten 1-second rounds to a new wallet. The threshold is 80%, so a round lost
 * to registration latency at the start can't flip the outcome, while a multi-second outage does.
 */
async function createTenSecondDeal(page: Page): Promise<string> {
  const recipient = Keypair.generate().publicKey.toBase58()
  await page.getByLabel('Recipient address').fill(recipient)
  await page.getByLabel('Payment (SOL)').fill(String(ESCROW_SOL))
  await page.getByLabel('Provider guarantee (SOL)').fill('0')
  await page.getByLabel('Window (seconds)').fill('10')
  await page.getByLabel('Check interval (seconds)').fill('1')
  await page.getByLabel('Minimum uptime (%)').fill('80')
  await page.getByRole('button', { name: 'Create deal' }).click()
  await expect(page.getByTestId('deal-verdict')).toContainText('Monitoring', { timeout: 20_000 })
  return recipient
}

/** Reads the UP count from the on-chain counters line ("9 up · 1 down · …"). */
async function upRounds(page: Page): Promise<number> {
  return Number((await page.getByTestId('deal-counters').innerText()).split(' ')[0])
}

test.afterEach(async ({ request }) => {
  await request.post(`${BACKEND_URL}/api/application/start`)
})

test('the program pays the recipient when on-chain observations meet the threshold', async ({ page }, testInfo) => {
  const payer = await connectFundedWallet(page)
  const recipient = await createTenSecondDeal(page)
  expect(await connection.getBalance(new PublicKey(recipient))).toBe(0)

  // Observations land on chain while the window runs: the counters come from the deal account.
  await expect.poll(() => upRounds(page), { timeout: 20_000 }).toBeGreaterThan(0)

  await expect(page.getByTestId('deal-verdict')).toHaveText('SLA met · escrow paid to recipient', { timeout: SETTLE_TIMEOUT_MS })
  expect(await upRounds(page)).toBeGreaterThanOrEqual(8)
  await expect(page.getByTestId('recipient-balance')).toHaveText(`${ESCROW_SOL} SOL`)

  // The money really moved on chain: the recipient got exactly the escrow, the payer paid it plus fees.
  expect(await connection.getBalance(new PublicKey(recipient))).toBe(ESCROW_SOL * LAMPORTS_PER_SOL)
  const payerBalance = await connection.getBalance(new PublicKey(payer))
  expect(payerBalance).toBeLessThanOrEqual((2 - ESCROW_SOL) * LAMPORTS_PER_SOL)
  expect(payerBalance).toBeGreaterThan((2 - ESCROW_SOL - 0.001) * LAMPORTS_PER_SOL)
  await testInfo.attach('paid', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
})

test('the program pays the payer when an outage drops uptime below the threshold', async ({ page }, testInfo) => {
  const payer = await connectFundedWallet(page)
  const recipient = await createTenSecondDeal(page)

  await page.getByRole('button', { name: 'Simulate outage' }).click()
  await expect(page.getByTestId('service-state')).toHaveText('DOWN')
  await page.waitForTimeout(4_000) // at least three whole rounds observed DOWN
  await page.getByRole('button', { name: 'Restore service' }).click()
  await expect(page.getByTestId('service-state')).toHaveText('UP')

  await expect(page.getByTestId('deal-verdict')).toHaveText('SLA breached · escrow paid to payer', { timeout: SETTLE_TIMEOUT_MS })
  await expect(page.getByTestId('deal-counters')).not.toContainText(' 0 down')
  expect(await connection.getBalance(new PublicKey(recipient))).toBe(0)
  // Only fees left the payer: the escrow and the deal rent came back.
  expect(await connection.getBalance(new PublicKey(payer))).toBeGreaterThan((2 - 0.001) * LAMPORTS_PER_SOL)
  await testInfo.attach('breached', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
})
