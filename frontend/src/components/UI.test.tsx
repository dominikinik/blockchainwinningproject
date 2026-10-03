import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { EmptyState, ErrorState, LoadingState, Notice, ResultBadge, SectionHeading, StatusBadge } from './UI'

describe('UI components', () => {
  it.each([
    ['pending', 'Pending'], ['healthy', 'Healthy'], ['at-risk', 'At Risk'], ['violated', 'Violated'], ['completed', 'Completed'],
  ] as const)('StatusBadge %s', (status, label) => {
    render(<StatusBadge status={status} />)
    expect(screen.getByText(label).className).toContain(`status-${status}`)
  })

  it('ResultBadge uppercases the result', () => {
    render(<ResultBadge result="down" />)
    expect(screen.getByText('DOWN').className).toContain('result-down')
  })

  it('SectionHeading renders optional subtitle and action', () => {
    const { rerender } = render(<SectionHeading title="T" />)
    expect(screen.getByRole('heading', { name: 'T' })).toBeInTheDocument()
    expect(screen.queryByText('Sub')).not.toBeInTheDocument()
    rerender(<SectionHeading title="T" subtitle="Sub" action={<button>Act</button>} />)
    expect(screen.getByText('Sub')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Act' })).toBeInTheDocument()
  })

  it('EmptyState renders title, description and action', () => {
    render(<EmptyState title="Nothing" description="Empty here" action={<a href="/x">Go</a>} />)
    expect(screen.getByRole('heading', { name: 'Nothing' })).toBeInTheDocument()
    expect(screen.getByText('Empty here')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go' })).toBeInTheDocument()
  })

  it('ErrorState shows retry only when provided and calls it', async () => {
    const retry = vi.fn()
    const { rerender } = render(<ErrorState message="Bad" />)
    expect(screen.getByText('Bad')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    rerender(<ErrorState message="Bad" retry={retry} />)
    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(retry).toHaveBeenCalledTimes(1)
  })

  it('LoadingState uses default and custom labels', () => {
    const { rerender } = render(<LoadingState />)
    expect(screen.getByText('Loading data...')).toBeInTheDocument()
    rerender(<LoadingState label="Working" />)
    expect(screen.getByText('Working...')).toBeInTheDocument()
  })

  it('Notice renders children', () => {
    render(<Notice>Heads up</Notice>)
    expect(screen.getByText('Heads up')).toBeInTheDocument()
  })
})
