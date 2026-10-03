import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderAt } from '../test/utils'
import { NotFoundPage } from './NotFoundPage'

describe('NotFoundPage', () => {
  it('shows message and link back to dashboard', () => {
    renderAt(<NotFoundPage />, '/whatever')
    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /back to dashboard/i })).toHaveAttribute('href', '/')
  })
})
