export function shortAddress(address: string, leading = 4, trailing = 4) {
  if (address.length <= leading + trailing + 3) return address
  return `${address.slice(0, leading)}...${address.slice(-trailing)}`
}

export function formatSol(amount: number) {
  return `${new Intl.NumberFormat('en-US', { maximumFractionDigits: 9 }).format(amount)} SOL`
}

export function totalEscrowSol(customerPaymentSol: number, providerGuaranteeSol: number) {
  return customerPaymentSol + providerGuaranteeSol
}

export function formatDate(value: string, includeSeconds = false) {
  return new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
    ...(includeSeconds ? { second: '2-digit' } : {}),
  }).format(new Date(value))
}

export function timeRemaining(value: string) {
  const minutes = Math.ceil((new Date(value).getTime() - Date.now()) / 60_000)
  if (minutes <= 0) return 'Ended'
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const mins = minutes % 60
  if (days) return `${days}d ${hours}h`
  if (hours) return `${hours}h ${mins}m`
  return `${mins}m`
}

export function timeRemainingPrecise(value: string, now: number) {
  const seconds = Math.ceil((new Date(value).getTime() - now) / 1000)
  if (seconds <= 0) return 'Ended'
  if (seconds < 3600) {
    const minutes = Math.floor(seconds / 60)
    const remainingSeconds = seconds % 60
    return minutes ? `${minutes}m ${remainingSeconds}s` : `${remainingSeconds}s`
  }
  return timeRemaining(value)
}

export function timeAgo(value: string) {
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000))
  if (seconds < 60) return `${seconds} sec ago`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`
  return `${Math.floor(seconds / 3600)} hr ago`
}

/**
 * Builds a Solana Explorer link on the cluster an RPC URL belongs to.
 *
 * @param kind a transaction signature or an account address
 * @param value the signature or address
 * @param rpcUrl the RPC URL the app uses; devnet when omitted
 * @returns a devnet, testnet or mainnet link for those clusters (any URL naming them, e.g. a hosted devnet
 *   RPC), otherwise a custom-cluster link that points the explorer at `rpcUrl` (localnet)
 */
export function explorerUrl(kind: 'tx' | 'address', value: string, rpcUrl?: string) {
  const base = `https://explorer.solana.com/${kind}/${value}`
  if (!rpcUrl || /devnet/i.test(rpcUrl)) return `${base}?cluster=devnet`
  if (/testnet/i.test(rpcUrl)) return `${base}?cluster=testnet`
  if (/mainnet/i.test(rpcUrl)) return base
  return `${base}?cluster=custom&customUrl=${encodeURIComponent(rpcUrl)}`
}

export function explorerTxUrl(signature: string, rpcUrl?: string) {
  return explorerUrl('tx', signature, rpcUrl)
}
