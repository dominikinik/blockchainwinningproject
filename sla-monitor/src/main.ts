import { readFileSync } from 'node:fs'
import { Connection, Keypair } from '@solana/web3.js'
import { AnchorProgramClient } from './client.js'
import { parseConfig } from './config.js'
import { Monitor } from './monitor.js'
import { FileStateStore } from './state.js'

const log = (msg: string) => console.log(`${new Date().toISOString()} ${msg}`)

async function main() {
  const cfg = parseConfig(process.argv.slice(2), process.env)
  const keypair = Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(cfg.keypairPath, 'utf8'))))
  const client = new AnchorProgramClient(new Connection(cfg.rpcUrl, 'confirmed'), keypair, cfg.programId)
  const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))
  const monitor = new Monitor({
    client,
    store: new FileStateStore(cfg.stateFile, log),
    fetch: (url, init) => fetch(url, init),
    now: () => Math.floor(Date.now() / 1000),
    sleep,
    log,
    self: keypair.publicKey.toBase58(),
  })
  log(`monitor ${keypair.publicKey.toBase58()} on ${cfg.rpcUrl}, program ${cfg.programId.toBase58()}`)

  let stopped = false
  process.on('SIGINT', () => (stopped = true))
  process.on('SIGTERM', () => (stopped = true))

  // Checks run on a 1s cadence, independent of (slow) RPC work; in-flight checks are de-duplicated.
  const checks = setInterval(() => void monitor.runChecks().catch((e) => log(`checks: ${e}`)), 1000)
  while (!stopped) {
    await monitor.refresh()
    await monitor.runReports()
    await monitor.runFinalize()
    await sleep(cfg.pollIntervalSecs * 1000)
  }
  clearInterval(checks)
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err)
  process.exit(1)
})
