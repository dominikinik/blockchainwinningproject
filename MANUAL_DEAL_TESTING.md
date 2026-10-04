# Manual testing: on-chain uptime deal

This guide walks through the real on-chain flow on a local Solana validator. It covers both outcomes:

- **Payout:** the service is up for the entire deal window, so the recipient gets the escrow.
- **Refund:** the service fails a health check during the window, so the payer gets the escrow back.

The page is `/deal`. This is separate from the mock SLA creation and settlement flows elsewhere in the frontend. Use two browser windows, one for the payer and one for the provider. Each window gets its own Burner Wallet; do not reload either window during a deal.

## What you need

- Docker, for PostgreSQL (`monitor-db`).
- Java 21 or newer, Maven, Node, and the Rust / Solana / Anchor toolchain. `scripts/setup-toolchain.sh` installs Maven and the missing Solana-side tools. Its current check asks for Node 24 or newer.
- The `uptime_deal` program source and program ID from `uptime-deal/Anchor.toml`.

Localnet SOL has no monetary value. A local validator's ledger is separate from Devnet and is reset when started with `--reset`.

## Start the local demo

### Recommended: use the demo script

From the repository root:

```sh
./scripts/setup-toolchain.sh
cd uptime-deal && anchor build --ignore-keys
cd .. && ./scripts/run-deal-demo.sh
```

If port 8899 is occupied, choose a free RPC port, for example `DEMO_RPC_PORT=8893 ./scripts/run-deal-demo.sh`. The script passes it to the validator, monitor and frontend.

The `anchor build --ignore-keys` step is needed when the local deploy keypair does not match the program ID declared in source. It skips that local keypair check; it does not change the program ID. The demo script then starts a fresh validator on port `8899` with the built program, reuses or starts PostgreSQL (`monitor-db`), starts the health provider (`uptime-service` on :8080) and the monitor (`uptime-monitor` on :8082, the deal oracle), and starts the frontend with Burner Wallet enabled. It prints a ready message when all services respond. Press Ctrl-C in that terminal to stop the validator, both services, and the frontend. PostgreSQL remains running and keeps its data.

If `anchor build --ignore-keys` fails because the Solana platform tools are missing, rerun the build after network access is available; the first SBF build downloads those tools.

### If port 8899 is already occupied

Some devcontainer setups forward Surfpool on `8899`. Run the validator on another free port (for example `8891`) and set the same RPC URL in both the monitor and the frontend. Start five terminals from the repository root.

Terminal 1, build the program if needed and start the validator:

```sh
cd uptime-deal
mkdir -p .anchor
anchor build --ignore-keys
solana-test-validator --reset --quiet --ledger .anchor/demo-ledger \
  --rpc-port 8891 \
  --bpf-program EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r target/deploy/uptime_deal.so
```

Terminal 2, start PostgreSQL if it is not already running:

```sh
docker compose -f monitor-db/docker-compose.yml up -d --wait
```

Terminal 3, start the health provider (it needs no database and no RPC):

```sh
cd uptime-service
mvn spring-boot:run
```

Terminal 4, start the monitor (the deal oracle) against that validator. It calls the provider's `/api/health` once per round of each deal's check interval:

```sh
cd uptime-monitor
SOLANA_RPC_URL=http://127.0.0.1:8891 mvn spring-boot:run
```

Terminal 5, start the frontend in local Burner Wallet mode:

```sh
cd frontend
VITE_SOLANA_RPC_URL=http://127.0.0.1:8891 \
VITE_SOLANA_BURNER_WALLET=true npm run dev
```

Open [http://localhost:5173/deal](http://localhost:5173/deal). Vite reads `VITE_SOLANA_RPC_URL` at startup, so restart Vite after changing it. The monitor reads `SOLANA_RPC_URL` at startup too.

### Check that the services agree

The validator health endpoint should respond:

```sh
curl -i http://127.0.0.1:8891/health
```

The monitor's deal config should report the same RPC URL as the frontend:

```sh
curl http://localhost:8082/api/deals/config
```

For the alternate-port setup, its `rpcUrl` should match `DEMO_RPC_PORT` (for example `http://127.0.0.1:8893`). A network mismatch warning on the page means the frontend and backend are pointed at different clusters; fix the environment variables and restart both.

## Prepare the two wallets

Open `/deal` in two separate browser windows. Connect **Burner Wallet** in each window. Click **Airdrop 2 SOL** in both: the payer needs the payment and rent, and the provider needs the guarantee and transaction fees. In the provider window, click **Copy my address** and paste that public address into the payer window's **Recipient address** field. Keep both windows open; reloading creates a new Burner Wallet key.

## Test a successful payout

1. In the payer window, enter the provider's address, set **Payment** to `0.5` SOL, **Provider guarantee** to `0.1` SOL, **Window** to `20` seconds, **Check interval** to `2` seconds and **Minimum uptime** to `90%`.
2. Click **Create deal**. The payment is locked and the proposal appears under **Proposals for you** in the provider window.
3. In the provider window, click **View proposal**, then **Accept and lock guarantee**. The provider's `0.1` SOL is locked and the window starts.
4. Keep the service **UP**. The on-chain counters should show ten UP rounds. After the window and 10-second observation grace, the monitor should submit settlement. Either wallet can also click **Settle now** once it appears.

Expected result: **SLA met · escrow paid to recipient**. The provider receives the `0.5` SOL payment and gets its `0.1` SOL guarantee back, less transaction fees. The rule is uptime **at least** the configured threshold.

## Test a refund after an outage

1. Ensure the service is **UP**, then create and accept a second deal with the same terms and wallets.
2. As soon as the provider accepts, click **Simulate outage** and leave the service **DOWN** for at least six seconds, so several of its two-second rounds are observed as DOWN.
3. Click **Restore service**. The oracle sends the probe results as DOWN/UP observations. Once the on-chain DOWN count makes the threshold unreachable, the monitor may settle early; otherwise anyone can settle after expiry and the observation grace.

Expected result: **SLA breached · escrow paid to payer**. The payer receives the payment and provider guarantee, less transaction fees.

## What the page shows

- **Current state** reflects the uptime service's controllable UP/DOWN state.
- **Oracle** is the service key that signs settlement transactions.
- **On-chain counters** show UP, DOWN and unobserved rounds in the deal window. Unobserved rounds count as DOWN at settlement.
- **Deal** shows the program's terms and final payout after settlement.
- **Recipient balance** is read from the same RPC as the payer wallet.

The monitor stores deal registrations in `monitor-db`, so restarting it doesn't lose them. Its pending observation retries live in memory, however, and missed rounds count as DOWN. The on-chain deal can be settled by anyone after the window and observation grace, even if the monitor is down.

## Troubleshooting

| What you see | Likely cause and fix |
| --- | --- |
| `Attempt to debit an account but found no record of a prior credit` | The payer has no SOL on this validator. Click **Airdrop 2 SOL**, wait for the balance to update, and retry. After a page reload, reconnect and check the balance again; a fresh Burner Wallet may have a different key. |
| The page says the uptime service and app use different RPC URLs | Set `SOLANA_RPC_URL` for `uptime-monitor` and `VITE_SOLANA_RPC_URL` for Vite to the same URL, then restart both. |
| Airdrop, balance, or transaction requests time out | Confirm the validator is running and that the configured RPC port is free and correct. If `8899` is forwarded by a devcontainer, use the alternate-port steps above. |
| The API reports the program is missing or the deal is rejected as invalid | Build the program and start the validator with the exact program ID and `.so` path shown above. A reset validator has no prior program deployment unless it is loaded with `--bpf-program`. |
| The deal stays ACTIVE or settlement fails | Keep the monitor running; check its terminal for Solana RPC errors. Confirm the monitor's `/api/deals/config` RPC URL matches the frontend, and keep the validator running through settlement. |
| No Burner Wallet option appears | The frontend was not started with `VITE_SOLANA_BURNER_WALLET=true`. Stop and restart Vite with that variable set. |

## Switching this setup to Devnet for a demo

Changing the frontend URL alone is not enough. Before using Devnet:

1. Deploy the program to Devnet and confirm it is available at the program ID expected by the backend.
2. Start `uptime-monitor` with `SOLANA_RPC_URL=https://api.devnet.solana.com`.
3. Start the frontend with `VITE_SOLANA_RPC_URL=https://api.devnet.solana.com` and leave `VITE_SOLANA_BURNER_WALLET` unset (or set it to `false`).
4. Connect Phantom or Solflare configured for Devnet and fund the wallet with Devnet faucet SOL.
5. Check `/api/deals/config` and confirm it reports the Devnet RPC before creating a deal.

Devnet is shared and is not reset when a process restarts. Localnet keys, balances, program deployment, and deals do not carry over to Devnet.

## Automated end-to-end test

For an automated version of the payout and outage/refund scenarios, see `frontend/e2e/uptime-deal.spec.ts`. Run it from `frontend/` with `npm run test:e2e`; it requires the Playwright setup and local Solana toolchain described in that module's docs.
