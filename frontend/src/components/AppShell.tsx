import { Activity, ArrowUpRight, CircleDot, Handshake, LayoutDashboard, Menu, Plus, Radio, X } from 'lucide-react'
import { WalletMultiButton } from '@solana/wallet-adapter-react-ui'
import { Link, NavLink, Outlet } from 'react-router-dom'
import { useState } from 'react'
import { networkLabel } from '../config/solana'

const links = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/create', label: 'Create SLA', icon: Plus, end: false },
  { to: '/monitoring', label: 'Monitoring', icon: Radio, end: false },
  { to: '/deal', label: 'Uptime deal', icon: Handshake, end: false },
]

export function AppShell() {
  const [menuOpen, setMenuOpen] = useState(false)
  return <div className="app-shell min-h-screen flex flex-col">
    <header className="site-header">
      <div className="header-inner flex items-center">
        <Link to="/" className="brand" onClick={() => setMenuOpen(false)} aria-label="SLAna home">
          <span className="brand-symbol"><Activity size={20} strokeWidth={2.8} /></span>
          <span>SLAna<span className="brand-period">.</span></span>
        </Link>
        <nav className="desktop-nav" aria-label="Main navigation">
          {links.map(({ to, label, icon: Icon, end }) => <NavLink key={to} to={to} end={end} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}><Icon size={16} />{label}</NavLink>)}
        </nav>
        <div className="header-actions">
          <span className="network-pill"><CircleDot size={13} /> {networkLabel()}</span>
          <WalletMultiButton />
          <button className="mobile-menu-button" aria-label={menuOpen ? 'Close menu' : 'Open menu'} aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}>{menuOpen ? <X size={21} /> : <Menu size={21} />}</button>
        </div>
      </div>
      {menuOpen && <nav className="mobile-nav" aria-label="Mobile navigation">{links.map(({ to, label, icon: Icon, end }) => <NavLink key={to} to={to} end={end} onClick={() => setMenuOpen(false)} className={({ isActive }) => `mobile-nav-link ${isActive ? 'active' : ''}`}><Icon size={17} />{label}</NavLink>)}</nav>}
    </header>
    <main className="main-content w-full flex-1"><Outlet /></main>
    <footer className="site-footer"><div><span className="footer-brand"><span className="brand-period">✦</span> SLAna</span><span>Trust in the agreement. Verify on Solana.</span></div><span>Built for Solana Devnet <ArrowUpRight size={14} /></span></footer>
  </div>
}
