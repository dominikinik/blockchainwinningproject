import { PublicKey } from '@solana/web3.js'
import { loadIdl } from './idl.js'

export interface Config {
  keypairPath: string
  rpcUrl: string
  programId: PublicKey
  pollIntervalSecs: number
  stateFile: string
}

const FLAGS: Record<string, string> = {
  '--keypair': 'KEYPAIR_PATH',
  '--rpc-url': 'RPC_URL',
  '--program-id': 'PROGRAM_ID',
  '--poll-interval': 'POLL_INTERVAL_SECS',
  '--state-file': 'STATE_FILE',
}

/** Parses CLI flags (`--keypair x` or `--keypair=x`) over environment variables. Throws on bad input. */
export function parseConfig(argv: string[], env: Record<string, string | undefined>): Config {
  const v: Record<string, string | undefined> = { ...env }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]!
    const [flag, inline] = arg.split(/=(.*)/s, 2) as [string, string | undefined]
    const key = FLAGS[flag]
    if (!key) throw new Error(`unknown argument: ${arg}`)
    const value = inline ?? argv[++i]
    if (value === undefined || value === '') throw new Error(`${flag} needs a value`)
    v[key] = value
  }

  const keypairPath = v.KEYPAIR_PATH?.trim()
  if (!keypairPath) throw new Error('KEYPAIR_PATH is required (path to the monitor keypair JSON)')

  const rpcUrl = (v.RPC_URL?.trim() || 'http://localhost:8899')
  try {
    const u = new URL(rpcUrl)
    if (!['http:', 'https:'].includes(u.protocol)) throw new Error()
  } catch {
    throw new Error(`RPC_URL is not a valid http(s) URL: ${rpcUrl}`)
  }

  const programRaw = v.PROGRAM_ID?.trim() || loadIdl().address
  let programId: PublicKey
  try {
    programId = new PublicKey(programRaw)
  } catch {
    throw new Error(`PROGRAM_ID is not a valid public key: ${programRaw}`)
  }

  const pollRaw = v.POLL_INTERVAL_SECS?.trim() || '10'
  const pollIntervalSecs = Number(pollRaw)
  if (!Number.isInteger(pollIntervalSecs) || pollIntervalSecs < 1) {
    throw new Error(`POLL_INTERVAL_SECS must be a positive integer: ${pollRaw}`)
  }

  return {
    keypairPath,
    rpcUrl,
    programId,
    pollIntervalSecs,
    stateFile: v.STATE_FILE?.trim() || './monitor-state.json',
  }
}
