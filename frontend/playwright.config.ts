import { defineConfig } from '@playwright/test'
import { MONITOR_URL, PROGRAM_ID, PROVIDER_URL, RPC_URL, WEB_URL } from './e2e/env'

// The real stack, end to end: a local validator running the uptime_deal program, the Java
// uptime-service (health provider) and uptime-monitor (deal oracle, PostgreSQL from monitor-db on the
// monitor_test database), and Vite with the burner wallet.
// Needs `anchor build` in ../uptime-deal, Docker (monitor-db), Java 21 + Maven and the Solana CLI
// (scripts/setup-toolchain.sh).
const solanaBin = `${process.env.HOME}/.local/share/solana/install/active_release/bin`
const rpcPort = new URL(RPC_URL).port
const providerPort = new URL(PROVIDER_URL).port
const monitorPort = new URL(MONITOR_URL).port
const webPort = new URL(WEB_URL).port

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  // The tests share one provider, and the outage test switches it off for everyone.
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
      // Starts the provider in the background, then the monitor; the monitor only starts once the provider answers.
      command: `sh e2e/start-backend.sh ${providerPort} ${monitorPort} ${RPC_URL}`,
      url: `${MONITOR_URL}/actuator/health`,
      timeout: 300_000,
    },
    {
      command: `npm run dev -- --port ${webPort} --strictPort`,
      env: { VITE_SOLANA_RPC_URL: RPC_URL, VITE_SOLANA_BURNER_WALLET: 'true', SLANA_UPTIME_SERVICE_TARGET: PROVIDER_URL, SLANA_UPTIME_MONITOR_TARGET: MONITOR_URL },
      url: WEB_URL,
      timeout: 60_000,
    },
  ],
})
