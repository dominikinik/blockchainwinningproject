# Live demo on Solana Devnet: the uptime deal

This is the script for the live demo. It covers one full scenario: a user arrives, connects a wallet, performs an operation and sees the result, and each step is then shown as a **confirmed transaction on Devnet in Solana Explorer**. Everything happens on the `/deal` page, the one flow in the app that uses no mocks.

The scenario has two parties:

- the **payer** (customer) proposes a deal and locks a payment;
- the **provider** accepts and locks a guarantee, which starts the uptime window.

`uptime-monitor`, the oracle, measures the provider's uptime during the window and settles the deal on chain. If uptime is above 99%, the provider receives both deposits; otherwise the payer does.

---

## 0. Deployment facts

| What | Value |
| --- | --- |
| Cluster | Devnet, RPC `https://api.devnet.solana.com` |
| Program `uptime_deal` | [`FVYpjzHktSRkPeUMytDPwVnAXTEf1QqVymKTnqNqfDmr`](https://explorer.solana.com/address/FVYpjzHktSRkPeUMytDPwVnAXTEf1QqVymKTnqNqfDmr?cluster=devnet) |
| Upgrade authority | `Dc5HHvcJ4bdRzMBMuNkPPFqFFJ34F6votEFfpfckxz2H` (the deployer wallet; also used to fund wallets) |
| Oracle | The address in the **Oracle** row on `/deal`, or `oracle` in `GET /api/deals/config`. It is the key in `.oracle-keypair.json` in the directory `uptime-monitor` runs from. **Start the monitor from the same directory every time**, or set `MONITOR_ORACLE_KEYPAIR=/absolute/path`. A new directory means a new, unfunded oracle that can't settle. |

Neither the program keypair (`uptime-deal/target/deploy/uptime_deal-keypair.json`) nor the deployer wallet is in Git. Back both up.

---

## 1. One-time preparation

### 1.1 Two wallets

You need two **different** Solana addresses: one for the payer and one for the provider. Accounts you already have work, because an address is the same on every cluster. Only the wallet's network setting changes.

- **Phantom:** Settings → Developer Settings → turn on *Testnet Mode*, then select *Solana Devnet*.
- **Solflare:** Settings → Network → *Devnet*.

(Menu names change between wallet versions; look for "Testnet mode" / "Network".)

**Recommended setup:** two browser profiles side by side, for example Chrome profile "Payer" with Phantom and Chrome profile "Provider" with Solflare (or a second Phantom). Each window then always shows its own party.

*Alternative:* one Phantom with two accounts. This works, because the page follows the active account, but you must switch accounts in Phantom between steps. It's easy to get wrong on stage.

Never type a private key or recovery phrase into the page. The page only asks the wallet to sign.

### 1.2 Fund both wallets with Devnet SOL

| Wallet | Needs at least | Why |
| --- | --- | --- |
| Payer | payment + ~0.002 SOL | payment, the deal account's rent (≈0.0014 SOL, returned when the deal closes) and fees |
| Provider | guarantee + ~0.001 SOL | guarantee and fees |

Use the demo amounts below (0.1 SOL each), and give each wallet **0.3 SOL or more** so you can repeat the demo. Sources:

- From the deployer wallet, on the machine that holds it:
  ```sh
  solana transfer <WALLET_ADDRESS> 0.3 --allow-unfunded-recipient --url devnet
  ```
- From https://faucet.solana.com (log in with GitHub for higher limits).

The page's **Airdrop 2 SOL** button uses the public faucet. On Devnet it is usually rate-limited, so don't rely on it on stage.

### 1.3 Fund the oracle

The oracle pays the fee for every `settle_deal` and downtime memo. Keep it **above 0.1 SOL**, because below that it tries an airdrop, which usually fails on Devnet:

```sh
curl -s http://localhost:8082/api/deals/config        # shows "oracle"
solana balance <ORACLE_ADDRESS> --url devnet
solana transfer <ORACLE_ADDRESS> 0.3 --allow-unfunded-recipient --url devnet   # if needed
```

---

## 2. Start the stack (demo day)

Start these in order. The monitor and the frontend must point at the **same** RPC URL.

1. **Database** (`monitor-db`, PostgreSQL on :5433):
   ```sh
   docker compose -f monitor-db/docker-compose.yml up -d --wait
   ```
   *On the Windows demo machine (WSL, no Docker),* a user-level PostgreSQL 17 lives in `~/.local/opt/pgsql` with data in `~/.local/share/monitor-db`:
   ```sh
   LD_LIBRARY_PATH=~/.local/opt/pgsql/lib ~/.local/opt/pgsql/bin/pg_ctl -D ~/.local/share/monitor-db \
     -o "-p 5433 -k /tmp -c listen_addresses=localhost" -l ~/.local/share/monitor-db/server.log start
   ```

2. **Provider health service** (`uptime-service`, :8080):
   ```sh
   cd uptime-service && mvn spring-boot:run
   ```

3. **Oracle** (`uptime-monitor`, :8082) on Devnet, polling the chain every 3 s instead of the localnet default of 0.5 s:
   ```sh
   cd uptime-monitor && SOLANA_RPC_URL=https://api.devnet.solana.com \
     mvn spring-boot:run -Dspring-boot.run.arguments="--monitor.deal.poll-interval-ms=3000"
   ```
   The public Devnet RPC limits requests per IP. The monitor doesn't back off on `429`, and at 0.5 s a single open proposal kept the whole machine's IP blocked, including the browser's wallet transactions. Settlement can then come up to 3 s later, which is fine for a 20 s window.

   *WSL + OneDrive:* Maven can't write `target/` under `/mnt/c/.../OneDrive` ("Operation not permitted"). Run both Java modules from a copy in the WSL home instead, and keep using the same copy so the oracle key stays the same:
   ```sh
   mkdir -p ~/build/uptime-monitor
   (cd /mnt/c/Users/<you>/OneDrive/Desktop/blockchainwinningproject/uptime-monitor && \
     tar --exclude=./target --exclude=./.oracle-keypair.json -cf - .) | (cd ~/build/uptime-monitor && tar -xf -)
   cd ~/build/uptime-monitor && SOLANA_RPC_URL=https://api.devnet.solana.com mvn spring-boot:run
   ```
   (Do the same for `uptime-service`.)

4. **Frontend** (:5173) with real wallets. Don't set `VITE_SOLANA_BURNER_WALLET`.
   - PowerShell:
     ```powershell
     cd frontend; $env:VITE_SOLANA_RPC_URL='https://api.devnet.solana.com'; npm run dev
     ```
   - bash:
     ```sh
     cd frontend && VITE_SOLANA_RPC_URL=https://api.devnet.solana.com npm run dev
     ```

### Sanity checks

- `curl http://localhost:8082/api/deals/config` returns `"programId":"FVYpjz…fDmr"` and `"rpcUrl":"https://api.devnet.solana.com"`.
- At http://localhost:5173/deal:
  - the header pill says **Solana Devnet**, and there is **no red banner** about different RPC URLs;
  - the **Uptime service** card shows *Current state: UP*;
  - **Oracle** and **Program** are links to the explorer.

---

## 3. Checklist, 30 minutes before

- [ ] Stack running; sanity checks pass.
- [ ] Oracle balance > 0.1 SOL.
- [ ] Payer and provider wallets are on **Devnet**, each with ≥ 0.3 SOL.
- [ ] Two browser windows on `/deal` (Payer left, Provider right), both connected. A third tab has Solana Explorer open, with the cluster set to **Devnet** (top right).
- [ ] One full dry run done end to end. Keep its explorer links as a backup.
- [ ] Wallet popups won't be hidden by screen sharing: share the whole screen, not a single tab.
- [ ] Notifications muted; browser zoom ~110% so the audience can read the deal card.

---

## 4. The live scenario, step by step (≈ 3 minutes)

Layout: **Payer** window on the left, **Provider** window on the right, Explorer in a tab.

### Step 1: The user arrives

Open http://localhost:5173 and click **Uptime deal** in the navigation (`/deal`).

> "The customer pays for a service and the provider puts up a guarantee. Both lock SOL in a program on Solana. An oracle measures the uptime and the program pays out. Nobody in the middle holds the money."

Point at:
- the **Uptime service** card: the provider's live state, checked every 2 s;
- the **Oracle** row: the key that will settle;
- the **Program** row: click it to show the deployed program on Devnet.

### Step 2: Connect the wallets

1. **Provider window:** click **Select Wallet** → **Phantom** (or Solflare) → approve **Connect** in the wallet popup. The form now shows *Wallet*, *Wallet balance* and **Copy my address**.
2. Click **Copy my address**. This is the address the customer will propose the deal to.
3. **Payer window:** click **Select Wallet** → choose the wallet → **Connect**. Show *Wallet balance*, for example 0.3 SOL.

> "This is a regular Solana wallet on Devnet. The page never sees a private key; every transaction is signed in the wallet."

### Step 3: The payer proposes the deal (transaction 1: `create_deal`)

In the **Payer** window:

1. **Provider address:** paste the provider's address.
2. **Payment (SOL):** `0.1`.
3. **Provider guarantee (SOL):** `0.1`.
4. **Window (seconds):** `20`. That is long enough to narrate; short enough to wait for. 1–3600 is allowed.
5. Click **Propose deal**. The wallet popup shows the transaction (about −0.1014 SOL: payment + rent). Click **Approve**.

Within a few seconds the **Deal** card appears:
- *Waiting for the provider to accept*;
- **Escrow:** 0.1 SOL;
- **Proposal tx**: a link.

**Show it on chain:** click the **Proposal tx** link and point at:
- the *Result* (Success) and its confirmation status, *Confirmed* and then *Finalized* after ~15 s;
- the *Signer*: the payer's address;
- the program `FVYpjz…`;
- *Account balance changes*: the payer −0.1014 SOL, and the new deal account +0.1014 SOL.

> "The payment is now locked in a program-owned account. Neither we nor the provider can touch it."

Explorer lists the instruction as *Unknown* because the IDL isn't uploaded. That is expected; the balance changes are the proof.

### Step 4: The provider accepts (transaction 2: `accept_deal`)

In the **Provider** window:

1. Within ~2 s, **Proposals for you** appears: *"<payer> pays 0.1 SOL · you lock 0.1 SOL · 20s"*. Click **Review**.
2. The **Deal** card shows *Payment*, *Provider guarantee* and *Window*. Click **Accept and lock 0.1 SOL** and approve in the wallet.
3. The verdict changes to **Measuring uptime · 19s left**. **Escrow** shows 0.2 SOL, and an **Acceptance tx** link appears.

> "The provider signed the same terms; the program refuses if the terms changed. The uptime window started on chain at the moment of acceptance."

Optional: open **Acceptance tx**. It shows the provider −0.1 SOL and the deal account +0.1 SOL.

### Step 5: The oracle measures and settles (transaction 3: `settle_deal`)

Wait for the countdown in either window. About 2–5 s after the window ends, the card shows:
- **Paid to recipient**;
- **Measured:** `20/20 s up (100.0%)`;
- **Settlement tx**: a link (visible in both windows);
- **Provider balance**, now up by 0.2 SOL.

> "The service was up every second, above the 99% threshold, so the program paid both deposits to the provider."

### Step 6: Prove it on chain

1. Click **Settlement tx**. Show:
   - *Signer* = the oracle (the same address as the **Oracle** row on the page);
   - *Account balance changes*: the provider **+0.2 SOL**, the deal account **−0.2014** (closed), the payer +0.0014 (rent back).
2. Click the deal **Address** link. The account no longer exists (it was closed on settlement), but its transaction history lists all three transactions: create → accept → settle.
3. Optional: the **Program** link lists recent transactions of the program.

> "Three transactions, three signers: customer, provider and oracle. Every lamport is accounted for on chain."

**Which window shows which link:** each window lists the transactions *its own wallet* signed for the deal (**Proposal tx** in the payer window, **Acceptance tx** in the provider window). Both windows show **Settlement tx**.

---

## 5. Optional second scenario: outage → refund (≈ 1.5 minutes)

It shows the other outcome and the oracle's downtime evidence.

1. **Payer:** propose a new deal (same provider, 0.1 / 0.1, window `30`).
2. **Provider:** **Review** → **Accept and lock 0.1 SOL**.
3. Right after acceptance, click **Simulate outage** on the *Uptime service* card (either window). *Current state* becomes **DOWN**.
4. Wait **at least 3 seconds**: the monitor checks every 2 s. Then click **Restore service**.
5. The deal doesn't wait for the window to end. One failed second makes "> 99%" impossible, so the oracle settles at once:
   - **Refunded to payer**;
   - **Measured** below 99%, for example `27/30 s up`. The down seconds count against the provider.
6. On chain:
   - **Settlement tx**: the payer receives both deposits (+0.2 SOL + rent) and the provider gets nothing.
   - Open the **Oracle** link: its history shows **Memo** transactions next to the settlement. Their JSON is the downtime report (`"app":"uptime-monitor"`, `totalDowntimeMs`, …), written on chain by the oracle.

The rule is **strictly greater than 99%**. For any window under 100 s, a single down second means a refund.

---

## 6. Short narration script

1. *Problem:* SLAs are promises; when they're broken the customer has to chase compensation.
2. *Solution:* both sides lock money in a Solana program. An oracle reports measured uptime, and the program pays automatically.
3. *Live:* connect wallets → propose → accept → measure → settle. Each step is a transaction on Devnet; here it is in the explorer.
4. *Trust model:* the program checks the terms and does the payout math. The oracle only reports numbers. If the oracle disappears, either party can reclaim its deposit 10 minutes after the window ends (**Reclaim deposits**).

---

## 7. Troubleshooting

| What you see | Cause and fix |
| --- | --- |
| Red banner "The uptime service settles on …, but this app is connected to …" | The monitor and the frontend use different RPC URLs. Set `SOLANA_RPC_URL` (monitor) and `VITE_SOLANA_RPC_URL` (Vite) to the exact same URL and restart both. |
| Header says *Solana Localnet* / *Custom RPC* | Vite was started without the Devnet URL. Restart it with `VITE_SOLANA_RPC_URL=https://api.devnet.solana.com`. |
| Wallet popup: insufficient funds / *Attempt to debit an account but found no record of a prior credit* | The wallet has no SOL **on Devnet**, or the wallet itself is still on Mainnet. Switch it to Devnet and fund it (1.2). |
| Wallet warns it could not simulate, or that the transaction may be unsafe | Common on Devnet with an unverified program. Check that the amounts match, then approve. |
| No **Proposals for you** in the provider window | The payer typed another address, or the provider window is connected to a different account. Compare with **Copy my address**. The payer can also send **Copy link**, which opens the deal directly. |
| Card stays on *Settling on chain…* | The oracle has no SOL, or the RPC is rate-limiting. Check the oracle balance and the monitor log. Deposits are never stuck: after window end + 10 min, **Reclaim deposits** returns each deposit. |
| Wallet or page error `failed to get recent blockhash: 429 … Connection rate limits exceeded` | This machine's IP is rate-limited by the public Devnet RPC, usually because the monitor polls too fast (see step 2.3). Restart the monitor with `--monitor.deal.poll-interval-ms=3000`, wait ~30 s, and retry. On shared venue Wi-Fi, use a dedicated RPC (next row). |
| `429 Too Many Requests` in the monitor log, or slow confirmations | The public Devnet RPC is rate-limiting. Use a free hosted Devnet RPC (Helius, QuickNode) and set it as **both** `SOLANA_RPC_URL` and `VITE_SOLANA_RPC_URL`. Explorer links still go to Devnet, and the API key is never put in a link. |
| Explorer: *Transaction not found* | Check that the explorer's cluster is **Devnet**; the page's links set it. Right after sending, wait a second and refresh. |
| *Uptime service unavailable* on the page | `uptime-monitor` isn't running or isn't reachable on :8082. On Windows + WSL, `localhost` works where `127.0.0.1` sometimes doesn't. |

---

## 8. Fallbacks

- **Rehearsal links.** Keep the explorer links from the dry run open in tabs. If Devnet is down during the talk, they still prove real confirmed transactions. Links from the automated rehearsal on 2026-10-04:
  - payout: [create_deal](https://explorer.solana.com/tx/2mgdH3Ec3ddCmw4fwJNqJ9eqgoRUWSxohtw91VUDjKPBUbwmHiJCiK5mKMKh1swDsD3WDuLmtGwpr9SDpCvjwBKT?cluster=devnet) · [accept_deal](https://explorer.solana.com/tx/5odDvnRwo6xJ9AR85a3W8FqumExkkzLEMCp5qerrU6JbCzff7p6f2B6ox76rY57wMpkyc3yvzeERvCZXavqyDLhr?cluster=devnet) · [settle_deal](https://explorer.solana.com/tx/3UmPWMKyFd9h9Eo43FhtvNEP85fy2EypCa8tckGfEQkFFAggMiDL8pYRsGjYiiFnnCFBokaUEGe7EgZRG79aEqF8?cluster=devnet)
  - refund: [create_deal](https://explorer.solana.com/tx/EGj5hpr8Vvi5gDUWA1erwP1a4W6aSCuAKVT1moxhJamPSv1xqNfdkd1ho8ezAqeJbwhK9yuEZraee16TGQ2TJwd?cluster=devnet) · [accept_deal](https://explorer.solana.com/tx/34HLJjiHhNWFpu91hxaDZ8Ek6UPnntFJFQYqAhQ9PYnjC9Bv4KUU6bcxwxYgqZuswP9ug1fxNUs8xUJwJ49uecDR?cluster=devnet) · [settle_deal](https://explorer.solana.com/tx/5bREZkS9NbvSy9RSot9nCBtE7rFz3pcpDsofW725f3U7NgdAGb8T8MLm637xpg1Zp2GdbhYpjWtiDHQAxUMkmaAE?cluster=devnet)
- **Localnet.** `scripts/run-deal-demo.sh` runs the same flow on a local validator with burner wallets (see [MANUAL_DEAL_TESTING.md](MANUAL_DEAL_TESTING.md)). Its explorer links point Solana Explorer at the local RPC as a custom cluster.

---

## 9. Don't

- Don't demo **Dashboard**, **Create SLA** or SLA details pages. They run on mock data, and their transaction hashes are fake, so explorer links would 404. **Monitoring** is live (the provider's uptime timeline) and fine to show.
- Don't switch a wallet to Mainnet during the demo.
- Don't reuse the deployer wallet as the payer or provider; keep it for funding and upgrades.
