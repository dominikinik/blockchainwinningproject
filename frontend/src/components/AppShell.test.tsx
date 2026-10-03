import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { MemoryRouter } from 'react-router-dom'
import { render } from '@testing-library/react'
import { AppShell } from './AppShell'

vi.mock('@solana/wallet-adapter-react-ui', () => ({ WalletMultiButton: () => <button>Wallet</button> }))

function setup(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes><Route element={<AppShell />}><Route path="*" element={<p>page body</p>} /></Route></Routes>
    </MemoryRouter>,
  )
}

describe('AppShell', () => {
  it('renders brand, nav links, wallet button, outlet and footer', () => {
    setup()
    expect(screen.getByRole('link', { name: 'SLAna home' })).toHaveAttribute('href', '/')
    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/')
    expect(within(nav).getByRole('link', { name: 'Create SLA' })).toHaveAttribute('href', '/create')
    expect(within(nav).getByRole('link', { name: 'Monitoring' })).toHaveAttribute('href', '/monitoring')
    expect(within(nav).getByRole('link', { name: 'Uptime deal' })).toHaveAttribute('href', '/deal')
    expect(screen.getByRole('button', { name: 'Wallet' })).toBeInTheDocument()
    expect(screen.getByText('Solana Devnet')).toBeInTheDocument()
    expect(screen.getByText('page body')).toBeInTheDocument()
    expect(screen.getByText(/Trust in the agreement/)).toBeInTheDocument()
  })

  it('marks only the current route active (Dashboard is exact)', () => {
    setup('/create')
    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).getByRole('link', { name: 'Create SLA' }).className).toContain('active')
    expect(within(nav).getByRole('link', { name: 'Dashboard' }).className).not.toContain('active')
  })

  it('toggles the mobile menu and closes it on navigation', async () => {
    setup()
    expect(screen.queryByRole('navigation', { name: 'Mobile navigation' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Open menu' }))
    const mobile = screen.getByRole('navigation', { name: 'Mobile navigation' })
    expect(screen.getByRole('button', { name: 'Close menu' })).toHaveAttribute('aria-expanded', 'true')
    await userEvent.click(within(mobile).getByRole('link', { name: 'Monitoring' }))
    expect(screen.queryByRole('navigation', { name: 'Mobile navigation' })).not.toBeInTheDocument()
  })
})
