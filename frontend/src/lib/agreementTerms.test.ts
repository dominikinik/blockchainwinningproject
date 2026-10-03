import { describe, expect, it } from 'vitest'
import { durationOptions, formatDuration } from './agreementTerms'

describe('durationOptions', () => {
  it('labels match formatDuration for every option', () => {
    for (const option of durationOptions) expect(formatDuration(option.value)).toBe(option.label)
  })
  it('is sorted from shortest to longest', () => {
    const values = durationOptions.map((option) => option.value)
    expect(values).toEqual([...values].sort((a, b) => a - b))
  })
})

describe('formatDuration', () => {
  it.each([
    [30 / 86_400, '30 seconds'],
    [1 / 86_400, '1 seconds'],
    [60 / 86_400, '1 minute'],
    [90 / 86_400, '1.5 minutes'],
    [15 * 60 / 86_400, '15 minutes'],
    [1 / 24, '1 hour'],
    [2 / 24, '2 hours'],
    [1, '1 day'],
    [7, '7 days'],
    [0, '0 seconds'],
  ])('formats %f days as %s', (days, label) => {
    expect(formatDuration(days)).toBe(label)
  })
  it('rounds to whole seconds', () => {
    expect(formatDuration(30.4 / 86_400)).toBe('30 seconds')
  })
})
