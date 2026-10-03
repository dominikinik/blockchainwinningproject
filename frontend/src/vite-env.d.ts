/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Solana JSON-RPC URL; defaults to Devnet. */
  readonly VITE_SOLANA_RPC_URL?: string
  /** "true" offers only an in-browser burner wallet (local testing; the key is thrown away). */
  readonly VITE_SOLANA_BURNER_WALLET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
