import { Buffer } from 'buffer'

// web3.js and wallet adapters use Buffer when building Solana transactions.
(globalThis as { Buffer?: typeof Buffer }).Buffer = Buffer
