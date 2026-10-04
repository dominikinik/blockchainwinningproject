import { clusterApiUrl } from '@solana/web3.js'

const rpcOverride = import.meta.env.VITE_SOLANA_RPC_URL

/** The cluster the wallet adapter and deal transactions use. */
export const SOLANA_RPC_URL: string = rpcOverride || clusterApiUrl('devnet')

/** Local testing mode: the only wallet is an unsafe in-browser burner, so no extension is needed. */
export const USE_BURNER_WALLET = import.meta.env.VITE_SOLANA_BURNER_WALLET === 'true'

/**
 * Labels the cluster for the header pill.
 *
 * @param rpcUrl the configured RPC URL override, if any
 * @returns "Solana Devnet" without an override or for a devnet RPC (public or hosted), "Solana Localnet" for a
 *   local node, otherwise "Custom RPC"
 */
export function networkLabel(rpcUrl: string | undefined = rpcOverride): string {
  if (!rpcUrl || /devnet/i.test(rpcUrl)) return 'Solana Devnet'
  return /\/\/(localhost|127\.0\.0\.1)[:/]/.test(rpcUrl) ? 'Solana Localnet' : 'Custom RPC'
}

function normalizeUrl(value: string): string | null {
  try {
    const url = new URL(value.trim())
    const host = url.hostname === 'localhost' ? '127.0.0.1' : url.hostname
    const port = url.port || (url.protocol === 'https:' ? '443' : url.protocol === 'http:' ? '80' : '')
    return `${url.protocol}//${host}:${port}${url.pathname.replace(/\/+$/, '')}`
  } catch {
    return null
  }
}

/**
 * Tells whether two RPC URLs point at the same cluster endpoint. Scheme, host, port and path must
 * match; `localhost` equals `127.0.0.1`, default ports are implied and a trailing slash is ignored.
 *
 * @param a one RPC URL
 * @param b another RPC URL
 * @returns true when they name the same endpoint; false when different or either isn't a valid URL
 */
export function sameCluster(a: string, b: string): boolean {
  const [x, y] = [normalizeUrl(a), normalizeUrl(b)]
  return x !== null && x === y
}
