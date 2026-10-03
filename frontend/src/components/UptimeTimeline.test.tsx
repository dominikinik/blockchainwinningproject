import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { UptimePoint } from '../services/uptime/uptimeService'
import { UptimeTimeline } from './UptimeTimeline'

const points = (count: number, downAt: number[] = []): UptimePoint[] => Array.from({ length: count }, (_, i) => ({
  time: new Date(Date.UTC(2026, 9, 3, 12, 0, 0) + i * 1000).toISOString(), down: downAt.includes(i),
}))
const blocks = (container: HTMLElement) => Array.from(container.querySelectorAll('.live-timeline-block'))

describe('UptimeTimeline', () => {
  it('shows an empty message without points', () => {
    const { container } = render(<UptimeTimeline points={[]} />)
    expect(screen.getByText('No recorded seconds are available yet.')).toBeInTheDocument()
    expect(blocks(container)).toHaveLength(0)
  })

  it('draws one block per second up to 60 points', () => {
    const { container } = render(<UptimeTimeline points={points(60, [5])} />)
    expect(blocks(container)).toHaveLength(60)
    expect(blocks(container).filter((b) => b.classList.contains('down'))).toHaveLength(1)
    expect(screen.getByText('1s per block')).toBeInTheDocument()
  })

  it('groups longer histories into at most 60 blocks, marking a block down if any second is down', () => {
    const { container } = render(<UptimeTimeline points={points(300, [0, 299])} />)
    expect(blocks(container)).toHaveLength(60)
    expect(screen.getByText('5s per block')).toBeInTheDocument()
    const down = blocks(container).map((b) => b.classList.contains('down'))
    expect(down[0]).toBe(true)
    expect(down[59]).toBe(true)
    expect(down.filter(Boolean)).toHaveLength(2)
  })

  it('handles a last group smaller than the others', () => {
    const { container } = render(<UptimeTimeline points={points(61)} />)
    expect(blocks(container)).toHaveLength(31)
    expect(screen.getByText('2s per block')).toBeInTheDocument()
  })

  it('labels the timeline with up/down counts', () => {
    render(<UptimeTimeline points={points(10, [1, 2])} />)
    expect(screen.getByRole('img', { name: /^8 up seconds and 2 down seconds from .+ to .+$/ })).toBeInTheDocument()
  })

  it('gives each block a tooltip with its state', () => {
    const { container } = render(<UptimeTimeline points={points(2, [1])} />)
    expect(blocks(container)[0].getAttribute('title')).toMatch(/UP throughout$/)
    expect(blocks(container)[1].getAttribute('title')).toMatch(/DOWN detected$/)
  })
})
