import { expect, test, type Browser, type Page } from '@playwright/test'
import { Connection, Keypair, LAMPORTS_PER_SOL, PublicKey } from '@solana/web3.js'
import { PROVIDER_URL, RPC_URL } from './env'

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
  return (await page.getByText(/^My address$/).locator('xpath=following-sibling::strong').getAttribute('title'))!
}

/**
 * Fills in a 10-second deal of five 2-second rounds. The threshold is 80%, so a round lost to registration
 * latency at the start can't flip the outcome, while a multi-second outage does.
 */
async function submitTenSecondDeal(page: Page, recipient: string, guaranteeSol: number): Promise<void> {
  await page.getByLabel('Recipient address').fill(recipient)
  await page.getByLabel('Payment (SOL)').fill(String(ESCROW_SOL))
  await page.getByLabel('Provider guarantee (SOL)').fill(String(guaranteeSol))
  await page.getByLabel('Window (seconds)').fill('10')
  await page.getByLabel('Check interval (seconds)').fill('2')
  await page.getByLabel('Minimum uptime (%)').fill('80')
  await page.getByRole('button', { name: 'Create deal' }).click()
}

/** Creates a 10-second deal without a guarantee to a new wallet, so its window starts at once. */
async function createTenSecondDeal(page: Page): Promise<string> {
  const recipient = Keypair.generate().publicKey.toBase58()
  await submitTenSecondDeal(page, recipient, 0)
  await expect(page.getByTestId('deal-verdict')).toContainText('Monitoring', { timeout: 20_000 })
  return recipient
}

/** Opens the deal page in its own browser context, so it gets its own burner wallet. */
async function openWalletWindow(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'] })
  return context.newPage()
}

/** Reads the UP count from the on-chain counters line ("9 up · 1 down · …"). */
async function upRounds(page: Page): Promise<number> {
  return Number((await page.getByTestId('deal-counters').innerText()).split(' ')[0])
}

test.afterEach(async ({ request }) => {
  await request.post(`${PROVIDER_URL}/api/application/start`)
})

test('the program pays the recipient when on-chain observations meet the threshold', async ({ page }, testInfo) => {
  const payer = await connectFundedWallet(page)
  const recipient = await createTenSecondDeal(page)
  expect(await connection.getBalance(new PublicKey(recipient))).toBe(0)

  // Observations land on chain while the window runs: the counters come from the deal account.
  await expect.poll(() => upRounds(page), { timeout: 20_000 }).toBeGreaterThan(0)

  await expect(page.getByTestId('deal-verdict')).toHaveText('SLA met · escrow paid to recipient', { timeout: SETTLE_TIMEOUT_MS })
  expect(await upRounds(page)).toBeGreaterThanOrEqual(4)
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

test('the provider accepts a proposal from another wallet and wins payment plus guarantee', async ({ browser }, testInfo) => {
  test.setTimeout(150_000)
  const GUARANTEE_SOL = 0.1
  const payerPage = await openWalletWindow(browser)
  const providerPage = await openWalletWindow(browser)
  const payer = await connectFundedWallet(payerPage)
  const provider = await connectFundedWallet(providerPage)
  expect(provider).not.toBe(payer)

  // The provider shares its address with the copy button.
  await providerPage.getByRole('button', { name: 'Copy my address' }).click()
  expect(await providerPage.evaluate(() => navigator.clipboard.readText())).toBe(provider)

  // A deal with a guarantee waits for the provider: no window runs yet.
  await submitTenSecondDeal(payerPage, provider, GUARANTEE_SOL)
  await expect(payerPage.getByTestId('deal-verdict')).toHaveText('Waiting for the provider to lock its guarantee', { timeout: 20_000 })
  await expect(payerPage.getByText("The provider hasn't accepted yet")).toBeVisible()
  // Only the provider is offered the proposal.
  await expect(payerPage.getByRole('region', { name: 'Proposals for you' })).toHaveCount(0)

  const proposals = providerPage.getByRole('region', { name: 'Proposals for you' })
  await expect(proposals).toBeVisible({ timeout: 10_000 })
  await expect(proposals.getByRole('button', { name: 'View proposal' })).toHaveCount(1)
  await proposals.getByRole('button', { name: 'View proposal' }).click()
  await expect(providerPage.getByTestId('deal-verdict')).toHaveText('Waiting for the provider to lock its guarantee', { timeout: 20_000 })
  await expect(providerPage.getByText(`Lock ${GUARANTEE_SOL} SOL to start the window`)).toBeVisible()
  await providerPage.getByRole('button', { name: 'Accept and lock guarantee' }).click()

  // Accepting starts the window for both sides, and the proposal leaves the provider's list.
  await expect(providerPage.getByTestId('deal-verdict')).toContainText('Monitoring', { timeout: 20_000 })
  await expect(payerPage.getByTestId('deal-verdict')).toContainText('Monitoring', { timeout: 20_000 })
  await expect(proposals).toHaveCount(0, { timeout: 10_000 })
  await expect.poll(() => upRounds(payerPage), { timeout: 20_000 }).toBeGreaterThan(0)

  await expect(payerPage.getByTestId('deal-verdict')).toHaveText('SLA met · escrow paid to recipient', { timeout: SETTLE_TIMEOUT_MS })
  await expect(providerPage.getByTestId('deal-verdict')).toHaveText('SLA met · escrow paid to recipient', { timeout: 20_000 })

  // The provider got the payment and its guarantee back, less fees; the payer paid the payment plus fees.
  const providerBalance = await connection.getBalance(new PublicKey(provider))
  expect(providerBalance).toBeLessThanOrEqual((2 + ESCROW_SOL) * LAMPORTS_PER_SOL)
  expect(providerBalance).toBeGreaterThan((2 + ESCROW_SOL - 0.001) * LAMPORTS_PER_SOL)
  const payerBalance = await connection.getBalance(new PublicKey(payer))
  expect(payerBalance).toBeLessThanOrEqual((2 - ESCROW_SOL) * LAMPORTS_PER_SOL)
  expect(payerBalance).toBeGreaterThan((2 - ESCROW_SOL - 0.001) * LAMPORTS_PER_SOL)
  await testInfo.attach('accepted-and-paid', { body: await payerPage.screenshot({ fullPage: true }), contentType: 'image/png' })
  await payerPage.context().close()
  await providerPage.context().close()
})
