import { expect, test, type Page } from '@playwright/test'
import { Connection, Keypair, LAMPORTS_PER_SOL, PublicKey } from '@solana/web3.js'
import { BACKEND_URL, RPC_URL } from './env'

const connection = new Connection(RPC_URL, 'confirmed')
const ESCROW_SOL = 0.5

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

/** Fills the form for a 10-second deal to a new wallet and submits it. */
async function createTenSecondDeal(page: Page): Promise<string> {
  const recipient = Keypair.generate().publicKey.toBase58()
  await page.getByLabel('Recipient address').fill(recipient)
  await page.getByLabel('Amount (SOL)').fill(String(ESCROW_SOL))
  await page.getByLabel('Window (seconds)').fill('10')
  await page.getByRole('button', { name: 'Create deal' }).click()
  await expect(page.getByTestId('deal-verdict')).toContainText('Measuring uptime', { timeout: 20_000 })
  return recipient
}

test.afterEach(async ({ request }) => {
  await request.post(`${BACKEND_URL}/api/application/start`)
})

test('pays the recipient when the service stays up for the whole 10-second window', async ({ page }, testInfo) => {
  const payer = await connectFundedWallet(page)
  const recipient = await createTenSecondDeal(page)
  expect(await connection.getBalance(new PublicKey(recipient))).toBe(0)

  await expect(page.getByTestId('deal-verdict')).toHaveText('Paid to recipient', { timeout: 40_000 })
  await expect(page.getByTestId('deal-measured')).toHaveText('10/10 s up (100.0%)')
  await expect(page.getByTestId('recipient-balance')).toHaveText(`${ESCROW_SOL} SOL`)

  // The money really moved on chain: the recipient got exactly the escrow, the payer paid it plus fees.
  expect(await connection.getBalance(new PublicKey(recipient))).toBe(ESCROW_SOL * LAMPORTS_PER_SOL)
  const payerBalance = await connection.getBalance(new PublicKey(payer))
  expect(payerBalance).toBeLessThanOrEqual((2 - ESCROW_SOL) * LAMPORTS_PER_SOL)
  expect(payerBalance).toBeGreaterThan((2 - ESCROW_SOL - 0.001) * LAMPORTS_PER_SOL)
  await testInfo.attach('paid', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
})

test('refunds the payer when the service goes down during the window', async ({ page }, testInfo) => {
  const payer = await connectFundedWallet(page)
  const recipient = await createTenSecondDeal(page)

  await page.getByRole('button', { name: 'Simulate outage' }).click()
  await expect(page.getByTestId('service-state')).toHaveText('DOWN')
  await page.waitForTimeout(2_000) // keep the service down for whole recorded seconds
  await page.getByRole('button', { name: 'Restore service' }).click()
  await expect(page.getByTestId('service-state')).toHaveText('UP')

  await expect(page.getByTestId('deal-verdict')).toHaveText('Refunded to payer', { timeout: 40_000 })
  await expect(page.getByTestId('deal-measured')).not.toContainText('10/10')
  expect(await connection.getBalance(new PublicKey(recipient))).toBe(0)
  // Only fees left the payer: the escrow and the deal rent came back.
  expect(await connection.getBalance(new PublicKey(payer))).toBeGreaterThan((2 - 0.001) * LAMPORTS_PER_SOL)
  await testInfo.attach('refunded', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
})
