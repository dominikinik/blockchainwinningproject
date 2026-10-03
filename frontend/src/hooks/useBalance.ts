import { useCallback, useEffect, useState } from 'react'
import { PublicKey, type Connection } from '@solana/web3.js'

/**
 * Polls an account balance.
 *
 * @param connection the cluster to read from
 * @param address Base58 address to watch; null or undefined clears the balance
 * @param intervalMs how often to refresh
 * @returns the balance in lamports (null until read or when unreadable) and a `refresh` function
 */
export function useBalance(connection: Connection, address: string | null | undefined, intervalMs = 2000) {
  const [lamports, setLamports] = useState<number | null>(null)

  const refresh = useCallback(async () => {
    if (!address) { setLamports(null); return }
    try {
      setLamports(await connection.getBalance(new PublicKey(address), 'confirmed'))
    } catch {
      setLamports(null)
    }
  }, [connection, address])

  useEffect(() => {
    void refresh()
    if (!address) return
    const interval = window.setInterval(() => void refresh(), intervalMs)
    return () => window.clearInterval(interval)
  }, [refresh, address, intervalMs])

  return { lamports, refresh }
}
