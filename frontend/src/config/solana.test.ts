import { describe, expect, it } from 'vitest'
import { networkLabel, SOLANA_RPC_URL, USE_BURNER_WALLET } from './solana'

describe('solana config', () => {
  it('defaults to Devnet with the regular wallets', () => {
    expect(SOLANA_RPC_URL).toBe('https://api.devnet.solana.com')
    expect(USE_BURNER_WALLET).toBe(false)
    expect(networkLabel()).toBe('Solana Devnet')
  })

  it.each([
    ['http://127.0.0.1:18899', 'Solana Localnet'],
    ['http://localhost:8899', 'Solana Localnet'],
    ['https://rpc.example.com', 'Custom RPC'],
    ['', 'Solana Devnet'],
  ])('labels %s as %s', (url, label) => {
    expect(networkLabel(url)).toBe(label)
  })
})
