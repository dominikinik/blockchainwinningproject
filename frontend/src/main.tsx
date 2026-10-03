import './polyfills'
import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { ConnectionProvider, WalletProvider } from '@solana/wallet-adapter-react'
import { WalletModalProvider } from '@solana/wallet-adapter-react-ui'
import { PhantomWalletAdapter } from '@solana/wallet-adapter-phantom'
import { SolflareWalletAdapter } from '@solana/wallet-adapter-solflare'
import { WalletAdapterNetwork } from '@solana/wallet-adapter-base'
import { UnsafeBurnerWalletAdapter } from '@solana/wallet-adapter-unsafe-burner'
import { SOLANA_RPC_URL, USE_BURNER_WALLET } from './config/solana'
import { App } from './App'
import '@solana/wallet-adapter-react-ui/styles.css'
import './styles.css'

// Local testing offers only a throwaway in-browser key, so no wallet extension is needed.
const wallets = USE_BURNER_WALLET
  ? [new UnsafeBurnerWalletAdapter()]
  : [new PhantomWalletAdapter(), new SolflareWalletAdapter({ network: WalletAdapterNetwork.Devnet })]

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConnectionProvider endpoint={SOLANA_RPC_URL}>
      <WalletProvider wallets={wallets} autoConnect>
        <WalletModalProvider>
          <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
            <App />
          </BrowserRouter>
        </WalletModalProvider>
      </WalletProvider>
    </ConnectionProvider>
  </React.StrictMode>,
)
