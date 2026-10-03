import { defineConfig } from '@playwright/test'
import { BACKEND_URL, PROGRAM_ID, RPC_URL, WEB_URL } from './e2e/env'

// The real stack, end to end: a local validator running the uptime_deal program, the Java uptime
// service as the deal oracle (on the uptime_test database), and Vite with the burner wallet.
// Needs `anchor build` in ../uptime-deal, Docker, Java 21 and the Solana CLI (scripts/setup-toolchain.sh).
const solanaBin = `${process.env.HOME}/.local/share/solana/install/active_release/bin`
const rpcPort = new URL(RPC_URL).port
const backendPort = new URL(BACKEND_URL).port
const webPort = new URL(WEB_URL).port

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  // The tests share one uptime service, and the outage test switches it off for everyone.
  workers: 1,
  fullyParallel: false,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: WEB_URL, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: [
    {
      command: `PATH="${solanaBin}:$PATH" solana-test-validator --reset --quiet --ledger e2e/.ledger `
        + `--rpc-port ${rpcPort} --faucet-port 19900 --gossip-port 19001 --dynamic-port-range 19002-19040 `
        + `--bpf-program ${PROGRAM_ID} ../uptime-deal/target/deploy/uptime_deal.so`,
      url: `${RPC_URL}/health`,
      timeout: 120_000,
    },
    {
      command: `sh e2e/start-backend.sh ${backendPort} ${RPC_URL}`,
      url: `${BACKEND_URL}/api/application/state`,
      timeout: 240_000,
    },
    {
      command: `npm run dev -- --port ${webPort} --strictPort`,
      env: { VITE_SOLANA_RPC_URL: RPC_URL, VITE_SOLANA_BURNER_WALLET: 'true', SLANA_UPTIME_SERVICE_TARGET: BACKEND_URL },
      url: WEB_URL,
      timeout: 60_000,
    },
  ],
})
