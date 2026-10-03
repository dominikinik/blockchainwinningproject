import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'

process.env.TZ = 'UTC'

afterEach(() => {
  cleanup()
  // Files marked `@vitest-environment node` have no window.
  if (typeof window !== 'undefined') window.localStorage.clear()
})

// React Router v6 future-flag notices are noise for tests.
beforeEach(() => {
  const warn = console.warn.bind(console)
  vi.spyOn(console, 'warn').mockImplementation((...args: unknown[]) => {
    if (typeof args[0] === 'string' && args[0].includes('React Router Future Flag Warning')) return
    warn(...args)
  })
})
