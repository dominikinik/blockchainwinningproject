import { describe, expect, it } from 'vitest'
import { networkLabel, sameCluster, SOLANA_RPC_URL, USE_BURNER_WALLET } from './solana'

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

describe('sameCluster', () => {
  it.each([
    ['http://127.0.0.1:8899', 'http://localhost:8899'],
    ['http://127.0.0.1:8899/', 'http://127.0.0.1:8899'],
    ['HTTPS://API.devnet.solana.com', 'https://api.devnet.solana.com:443/'],
    ['http://localhost', 'http://127.0.0.1:80'],
  ])('treats %s and %s as the same cluster', (a, b) => {
    expect(sameCluster(a, b)).toBe(true)
  })

  it.each([
    ['http://127.0.0.1:8899', 'http://127.0.0.1:18899'],
    ['http://127.0.0.1:8899', 'https://127.0.0.1:8899'],
    ['http://127.0.0.1:8899', 'https://api.devnet.solana.com'],
    ['https://rpc.example.com/a', 'https://rpc.example.com/b'],
    ['not a url', 'not a url'],
    ['', ''],
  ])('treats %s and %s as different clusters', (a, b) => {
    expect(sameCluster(a, b)).toBe(false)
  })
})
