import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { explorerTxUrl, formatDate, formatSol, shortAddress, timeAgo, timeRemaining, timeRemainingPrecise } from './format'

describe('shortAddress', () => {
  it('truncates long addresses with defaults', () => {
    expect(shortAddress('ABCDEFGHIJKLMNOPQRSTUVWXYZ')).toBe('ABCD...WXYZ')
  })
  it('honours custom leading/trailing', () => {
    expect(shortAddress('ABCDEFGHIJKLMNOPQRSTUVWXYZ', 6, 5)).toBe('ABCDEF...VWXYZ')
  })
  it('returns short strings and empty string unchanged', () => {
    expect(shortAddress('')).toBe('')
    expect(shortAddress('abc')).toBe('abc')
  })
  it('keeps strings at the boundary length (leading+trailing+3) and truncates one longer', () => {
    expect(shortAddress('12345678901')).toBe('12345678901')
    expect(shortAddress('123456789012')).toBe('1234...9012')
  })
})

describe('formatSol', () => {
  it('formats integers, grouping and decimals', () => {
    expect(formatSol(10)).toBe('10 SOL')
    expect(formatSol(1234.5)).toBe('1,234.5 SOL')
    expect(formatSol(0)).toBe('0 SOL')
  })
  it('rounds to at most 3 fraction digits', () => {
    expect(formatSol(1.23456)).toBe('1.235 SOL')
    expect(formatSol(0.0004)).toBe('0 SOL')
  })
})

describe('formatDate', () => {
  it('formats an ISO date in en-US short form', () => {
    expect(formatDate('2025-03-05T14:07:00Z')).toBe('Mar 5, 2025, 2:07 PM')
  })
  it('formats midnight', () => {
    expect(formatDate('2025-12-31T00:00:00Z')).toBe('Dec 31, 2025, 12:00 AM')
  })
  it('includes seconds when asked', () => {
    expect(formatDate('2025-03-05T14:07:09Z', true)).toBe('Mar 5, 2025, 2:07:09 PM')
  })
})

describe('time helpers', () => {
  const NOW = new Date('2025-01-01T00:00:00Z')
  const at = (ms: number) => new Date(NOW.getTime() + ms).toISOString()
  const MIN = 60_000
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(NOW) })
  afterEach(() => { vi.useRealTimers() })

  describe('timeRemaining', () => {
    it('returns Ended for now and the past', () => {
      expect(timeRemaining(at(0))).toBe('Ended')
      expect(timeRemaining(at(-5 * MIN))).toBe('Ended')
    })
    it('rounds partial minutes up', () => {
      expect(timeRemaining(at(1))).toBe('1m')
      expect(timeRemaining(at(59 * MIN + 1))).toBe('1h 0m')
    })
    it('shows minutes only under an hour', () => {
      expect(timeRemaining(at(45 * MIN))).toBe('45m')
    })
    it('shows hours and minutes under a day', () => {
      expect(timeRemaining(at(60 * MIN))).toBe('1h 0m')
      expect(timeRemaining(at(23 * 60 * MIN + 59 * MIN))).toBe('23h 59m')
    })
    it('shows days and hours from a day up', () => {
      expect(timeRemaining(at(1440 * MIN))).toBe('1d 0h')
      expect(timeRemaining(at((4 * 1440 + 12 * 60) * MIN))).toBe('4d 12h')
    })
  })

  describe('timeRemainingPrecise', () => {
    const now = NOW.getTime()
    it('returns Ended for now and the past', () => {
      expect(timeRemainingPrecise(at(0), now)).toBe('Ended')
      expect(timeRemainingPrecise(at(-1000), now)).toBe('Ended')
    })
    it('rounds partial seconds up and shows seconds only under a minute', () => {
      expect(timeRemainingPrecise(at(1), now)).toBe('1s')
      expect(timeRemainingPrecise(at(30_000), now)).toBe('30s')
      expect(timeRemainingPrecise(at(59_999), now)).toBe('1m 0s')
    })
    it('shows minutes and seconds under an hour', () => {
      expect(timeRemainingPrecise(at(61_000), now)).toBe('1m 1s')
      expect(timeRemainingPrecise(at(59 * MIN + 59_000), now)).toBe('59m 59s')
    })
    it('falls back to timeRemaining from an hour up', () => {
      expect(timeRemainingPrecise(at(60 * MIN), now)).toBe('1h 0m')
      expect(timeRemainingPrecise(at(1440 * MIN), now)).toBe('1d 0h')
    })
    it('uses the given now instead of the clock', () => {
      expect(timeRemainingPrecise(at(10_000), now + 4_000)).toBe('6s')
    })
  })

  describe('timeAgo', () => {
    it('shows seconds under a minute and clamps future to 0', () => {
      expect(timeAgo(at(-30_000))).toBe('30 sec ago')
      expect(timeAgo(at(0))).toBe('0 sec ago')
      expect(timeAgo(at(10_000))).toBe('0 sec ago')
    })
    it('switches to minutes at 60s', () => {
      expect(timeAgo(at(-59_000))).toBe('59 sec ago')
      expect(timeAgo(at(-60_000))).toBe('1 min ago')
      expect(timeAgo(at(-3599_000))).toBe('59 min ago')
    })
    it('switches to hours at 3600s', () => {
      expect(timeAgo(at(-3600_000))).toBe('1 hr ago')
      expect(timeAgo(at(-5 * 3600_000))).toBe('5 hr ago')
    })
  })
})

describe('explorerTxUrl', () => {
  it('builds a devnet explorer link', () => {
    expect(explorerTxUrl('abc123')).toBe('https://explorer.solana.com/tx/abc123?cluster=devnet')
  })
})
