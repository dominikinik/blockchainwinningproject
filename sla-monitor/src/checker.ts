export type FetchFn = (url: string, init?: { signal?: AbortSignal; method?: string }) => Promise<{
  status: number
  body?: { cancel(): Promise<void> } | null
}>

/**
 * One HTTP check. UP = 2xx within `timeoutMs`. A timeout or network error is DOWN
 * (the slot still counts as checked).
 */
export async function checkEndpoint(fetchFn: FetchFn, url: string, timeoutMs: number): Promise<boolean> {
  const ctrl = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<'timeout'>((resolve) => {
    timer = setTimeout(() => {
      ctrl.abort()
      resolve('timeout')
    }, timeoutMs)
  })
  try {
    const res = await Promise.race([fetchFn(url, { signal: ctrl.signal, method: 'GET' }), timeout])
    if (res === 'timeout') return false
    res.body?.cancel().catch(() => {})
    return res.status >= 200 && res.status < 300
  } catch {
    return false
  } finally {
    clearTimeout(timer)
  }
}
