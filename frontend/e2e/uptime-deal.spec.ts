import { expect, test, type Browser, type Page } from '@playwright/test'
import { Connection, LAMPORTS_PER_SOL, PublicKey } from '@solana/web3.js'
import { PROVIDER_URL, RPC_URL, WEB_URL } from './env'

const connection = new Connection(RPC_URL, 'confirmed')
const PAYMENT_SOL = 0.5
const GUARANTEE_SOL = 0.3
/** Upper bound for the fees and rent a wallet pays in one flow. */
const FEES_SOL = 0.001

/**
 * Opens the deal page in its own browser context, connects a fresh burner wallet and funds it. The page
 * must not reload afterwards: a reload gives the burner wallet a new key.
 */
async function connectFundedWallet(browser: Browser): Promise<{ page: Page, address: string }> {
  const context = await browser.newContext({ baseURL: WEB_URL })
  const page = await context.newPage()
  await page.goto('/deal')
  await page.getByRole('button', { name: 'Select Wallet' }).first().click()
  await page.getByRole('button', { name: /Burner Wallet/ }).click()
  await page.getByRole('button', { name: 'Airdrop 2 SOL' }).click()
  await expect(page.getByTestId('wallet-balance')).toHaveText('2 SOL', { timeout: 20_000 })
  await expect(page.getByTestId('service-state')).toHaveText('UP')
  return { page, address: (await page.getByTestId('wallet-address').getAttribute('title'))! }
}

/** The payer proposes a 10-second deal to the provider, which opens it from its "Proposals for you" list. */
async function propose(browser: Browser) {
  const provider = await connectFundedWallet(browser)
  const payer = await connectFundedWallet(browser)

  await payer.page.getByLabel('Provider address').fill(provider.address)
  await payer.page.getByLabel('Payment (SOL)').fill(String(PAYMENT_SOL))
  await payer.page.getByLabel('Provider guarantee (SOL)').fill(String(GUARANTEE_SOL))
  await payer.page.getByLabel('Window (seconds)').fill('10')
  await payer.page.getByRole('button', { name: 'Propose deal' }).click()
  await expect(payer.page.getByTestId('deal-verdict')).toHaveText('Waiting for the provider to accept', { timeout: 20_000 })

  await provider.page.getByRole('region', { name: 'Proposals for you' }).getByRole('button', { name: 'Review' }).click({ timeout: 10_000 })
  await expect(provider.page.getByTestId('deal-guarantee')).toHaveText(`${GUARANTEE_SOL} SOL`)
  return { payer, provider }
}

/** Proposes a deal and has the provider accept it, which starts the window. */
async function proposeAndAccept(browser: Browser) {
  const { payer, provider } = await propose(browser)
  await provider.page.getByRole('button', { name: `Accept and lock ${GUARANTEE_SOL} SOL` }).click()
  await expect(provider.page.getByTestId('deal-verdict')).toContainText('Measuring uptime', { timeout: 20_000 })
  await expect(payer.page.getByTestId('deal-verdict')).toContainText('Measuring uptime', { timeout: 5_000 })
  return { payer, provider }
}

test.afterEach(async ({ request }) => {
  await request.post(`${PROVIDER_URL}/api/application/start`)
})

test('pays the provider both deposits when the service stays up for the whole window', async ({ browser }, testInfo) => {
  const { payer, provider } = await proposeAndAccept(browser)

  await expect(payer.page.getByTestId('deal-verdict')).toHaveText('Paid to recipient', { timeout: 40_000 })
  await expect(payer.page.getByTestId('deal-measured')).toHaveText('10/10 s up (100.0%)')

  // The money really moved on chain: the provider got its guarantee back plus the payment.
  const providerBalance = await connection.getBalance(new PublicKey(provider.address))
  expect(providerBalance).toBeLessThanOrEqual((2 + PAYMENT_SOL) * LAMPORTS_PER_SOL)
  expect(providerBalance).toBeGreaterThan((2 + PAYMENT_SOL - FEES_SOL) * LAMPORTS_PER_SOL)
  const payerBalance = await connection.getBalance(new PublicKey(payer.address))
  expect(payerBalance).toBeLessThanOrEqual((2 - PAYMENT_SOL) * LAMPORTS_PER_SOL)
  expect(payerBalance).toBeGreaterThan((2 - PAYMENT_SOL - FEES_SOL) * LAMPORTS_PER_SOL)
  await testInfo.attach('paid', { body: await payer.page.screenshot({ fullPage: true }), contentType: 'image/png' })
})

test('pays the payer both deposits when the service goes down during the window', async ({ browser }, testInfo) => {
  const { payer, provider } = await proposeAndAccept(browser)

  await payer.page.getByRole('button', { name: 'Simulate outage' }).click()
  await expect(payer.page.getByTestId('service-state')).toHaveText('DOWN')
  // The monitor checks every 2 s and closes the deal early on the first Downtime, so keep the
  // provider down for longer than one check interval.
  await payer.page.waitForTimeout(3_000)
  await payer.page.getByRole('button', { name: 'Restore service' }).click()
  await expect(payer.page.getByTestId('service-state')).toHaveText('UP')

  await expect(payer.page.getByTestId('deal-verdict')).toHaveText('Refunded to payer', { timeout: 40_000 })
  await expect(payer.page.getByTestId('deal-measured')).not.toContainText('10/10')
  // The payer got its payment back plus the provider's guarantee; the provider lost the guarantee.
  const payerBalance = await connection.getBalance(new PublicKey(payer.address))
  expect(payerBalance).toBeGreaterThan((2 + GUARANTEE_SOL - FEES_SOL) * LAMPORTS_PER_SOL)
  expect(await connection.getBalance(new PublicKey(provider.address))).toBeLessThanOrEqual((2 - GUARANTEE_SOL) * LAMPORTS_PER_SOL)
  await testInfo.attach('refunded', { body: await payer.page.screenshot({ fullPage: true }), contentType: 'image/png' })
})

test('returns the payment when the provider rejects the proposal', async ({ browser }) => {
  const { payer, provider } = await propose(browser)
  await provider.page.getByRole('button', { name: 'Reject' }).click()
  await expect(provider.page.getByTestId('deal-verdict')).toHaveText('Cancelled · deposits returned', { timeout: 20_000 })
  await expect(payer.page.getByTestId('deal-verdict')).toHaveText('Cancelled · deposits returned', { timeout: 10_000 })
  expect(await connection.getBalance(new PublicKey(payer.address))).toBeGreaterThan((2 - FEES_SOL) * LAMPORTS_PER_SOL)
})
