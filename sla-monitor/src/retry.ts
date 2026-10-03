export interface RetryOptions {
  attempts: number
  baseMs: number
  maxMs: number
  sleep: (ms: number) => Promise<void>
}

export const DEFAULT_RETRY = { attempts: 4, baseMs: 500, maxMs: 8000 }

/** The SlaError code (6000+) carried by an Anchor/web3.js error, if any. */
export function programErrorCode(err: unknown): number | undefined {
  const e = err as { error?: { errorCode?: { number?: number } }; message?: string; logs?: string[] } | undefined
  const n = e?.error?.errorCode?.number
  if (typeof n === 'number') return n
  const text = `${e?.message ?? ''}\n${(e?.logs ?? []).join('\n')}`
  const hex = /custom program error: 0x([0-9a-f]+)/i.exec(text)
  if (hex) return parseInt(hex[1]!, 16)
  const dec = /Error Number: (\d+)/.exec(text)
  if (dec) return Number(dec[1])
  return undefined
}

/**
 * Runs `fn`, retrying transient (RPC/network) errors with exponential backoff.
 * Program errors are deterministic, so they are rethrown immediately.
 */
export async function withRetry<T>(fn: () => Promise<T>, opts: RetryOptions): Promise<T> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await fn()
    } catch (err) {
      if (programErrorCode(err) !== undefined || attempt >= opts.attempts) throw err
      await opts.sleep(Math.min(opts.baseMs * 2 ** (attempt - 1), opts.maxMs))
    }
  }
}
