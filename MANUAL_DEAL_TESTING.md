# Manual testing: on-chain uptime deal

This guide walks through the real on-chain flow on a local Solana validator. It covers both outcomes:

- **Payout:** the service is up for the entire deal window, so the recipient gets the escrow.
- **Refund:** the service fails a health check during the window, so the payer gets the escrow back.

The page is `/deal`. This is separate from the mock SLA creation and settlement flows elsewhere in the frontend.

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

Terminal 4, start the monitor (the deal oracle) against that validator. It calls the provider's `/api/health` every 2 s:

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

For the alternate-port setup, its `rpcUrl` should be `http://127.0.0.1:8891`. A network mismatch warning on the page means the frontend and backend are pointed at different clusters; fix the environment variables and restart both.

## Create a recipient address

The recipient can be any valid Solana public address that is different from the connected payer. The recipient does not need to connect or sign, and does not need funds beforehand. To keep a local recipient keypair for balance checks, create one once:

```sh
cd uptime-deal
mkdir -p .anchor
solana-keygen new --no-bip39-passphrase --silent --outfile .anchor/demo-recipient-keypair.json
solana-keygen pubkey .anchor/demo-recipient-keypair.json
```

Paste the printed public key into the page's **Recipient address** field. The keypair is ignored by Git under `.anchor/`; keep it local. Never paste the keypair file, private key, or recovery phrase into the page. If the file already exists, reuse it instead of running the `new` command again.

## Test a successful payout

1. Open `/deal`, click **Select Wallet**, and choose **Burner Wallet**. This is a throwaway in-browser wallet for local testing; no browser wallet extension is required.
2. Click **Airdrop 2 SOL**. Wait until the payer balance updates before submitting. A new or reset local ledger does not have the burner's previous SOL.
3. Enter the recipient public key, set **Amount** to `0.5` SOL, and set **Window** to `10` seconds. The amount must be at least `0.001` SOL; the recipient cannot equal the payer.
4. Click **Create deal** and approve the transaction if prompted.
5. Keep the uptime service in the **UP** state for the whole window. Wait for the deal to settle.

Expected result: **Paid to recipient**, with all five two-second rounds reported UP. The recipient balance should increase by `0.5 SOL`.

The program's rule is strictly **greater than 99%**. For a 10-second deal, all 10 seconds must be up to pay the recipient.

## Test a refund after an outage

1. Create a second 10-second deal using the same funded payer and a different recipient address.
2. As soon as the deal starts, click **Simulate outage**. Leave the service down for at least three seconds so the monitor's 2-second health check sees it.
3. Click **Restore service**. The monitor reports each failed/healthy probe directly as DOWN/UP for its completed round. Once the on-chain counters prove the 99% threshold is unreachable, the monitor can submit settlement early; otherwise anyone can settle after expiry.

Expected result: **Refunded to payer**; the recipient receives no SOL. At or below 99% uptime, the on-chain program closes the deal and returns the escrow to the payer. Transaction fees are still paid in local test SOL.

## What the page shows

- **Current state** reflects the uptime service's controllable UP/DOWN state.
- **Oracle** is the service key that signs settlement transactions.
- **Measured** is the uptime the monitor reported for the on-chain deal window, from its health checks.
- **Deal** updates from ACTIVE to SETTLED and shows whether the recipient was paid or the payer was refunded.
- **Recipient balance** is read from the same RPC as the payer wallet.

The monitor stores deals in `monitor-db`, so restarting it doesn't lose them; it carries on settling after it starts again. If it stays down, the on-chain escrow remains recoverable by the payer after the program's cancellation timeout (10 minutes after the deal window ends).

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
