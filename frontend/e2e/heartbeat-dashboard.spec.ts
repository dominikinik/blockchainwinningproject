import { expect, test } from '@playwright/test'
import { Keypair } from '@solana/web3.js'
import { PROVIDER_URL } from './env'

// Recorded: the video is the demo of the heartbeat log (docs/videos/heartbeat-dashboard.webm).
test.use({ viewport: { width: 1280, height: 860 }, video: { mode: 'on', size: { width: 1280, height: 860 } } })

test.afterEach(async ({ request }) => {
  await request.post(`${PROVIDER_URL}/api/application/start`)
})

test('the dashboard fills with the oracle heartbeats of a live deal, outage included', async ({ page, request }) => {
  test.setTimeout(120_000)

  // A 40-second deal of 2-second rounds with no guarantee, so its window starts at creation.
  await page.goto('/deal')
  await page.getByRole('button', { name: 'Select Wallet' }).first().click()
  await page.getByRole('button', { name: /Burner Wallet/ }).click()
  await page.getByRole('button', { name: 'Airdrop 2 SOL' }).click()
  await expect(page.getByTestId('wallet-balance')).toHaveText('2 SOL', { timeout: 20_000 })
  await page.getByLabel('Recipient address').fill(Keypair.generate().publicKey.toBase58())
  await page.getByLabel('Payment (SOL)').fill('0.5')
  await page.getByLabel('Provider guarantee (SOL)').fill('0')
  await page.getByLabel('Window (seconds)').fill('40')
  await page.getByLabel('Check interval (seconds)').fill('2')
  await page.getByLabel('Minimum uptime (%)').fill('50')
  await page.getByRole('button', { name: 'Create deal' }).click()
  await expect(page.getByTestId('deal-verdict')).toContainText('Monitoring', { timeout: 20_000 })

  // The dashboard polls /api/heartbeats every 2 s: one UP beat per ended round, each reported on chain.
  await page.getByRole('link', { name: 'Dashboard' }).first().click()
  const panel = page.locator('section.heartbeat-panel')
  await panel.scrollIntoViewIfNeeded()
  const rows = panel.getByTestId('heartbeat-row')
  await expect.poll(() => rows.count(), { timeout: 20_000 }).toBeGreaterThanOrEqual(3)
  await expect(rows.first()).toContainText('UP')
  await expect(rows.first()).toContainText('200')
  // A send that beats the validator's clock (RoundNotEnded) shows RETRYING until the next tick lands it.
  await expect(rows.nth(1)).toContainText('SENT', { timeout: 5_000 })
  await panel.scrollIntoViewIfNeeded()

  // Stopping the provider turns the next beats DOWN; restoring it turns them UP again.
  await request.post(`${PROVIDER_URL}/api/application/stop`)
  await expect(panel.locator('.heartbeat-bar.down').first()).toBeVisible({ timeout: 10_000 })
  await expect(rows.first()).toContainText('DOWN')
  await page.waitForTimeout(4_000)

  await request.post(`${PROVIDER_URL}/api/application/start`)
  await expect(rows.first()).toContainText('UP', { timeout: 10_000 })
  await page.waitForTimeout(4_000)

  const bars = panel.getByTestId('heartbeat-bar')
  expect(await bars.count()).toBeGreaterThanOrEqual(8)
  expect(await panel.locator('.heartbeat-bar.down').count()).toBeGreaterThanOrEqual(2)
  await expect(panel.getByText('UP rate')).toBeVisible()
})
