#!/bin/sh
# Starts the full uptime-deal stack for manual testing in the browser, and stops it on Ctrl-C:
#   - solana-test-validator on :8899 with the uptime_deal program (fresh ledger each run)
#   - uptime-service on :8080, the health provider (no database)
#   - uptime-monitor on :8082, the proxy that relays the provider's health to every deal naming its oracle
#   - the frontend on :5173 with the in-browser burner wallet
# Then open http://localhost:5173/deal in two windows (provider and payer). In each, connect "Burner Wallet"
# and press "Airdrop 2 SOL". The payer proposes a deal to the provider's address; the provider accepts it, and
# after the window either side presses "Settle now". Don't reload either window: that gives its burner wallet a new
# key. Needs Java 21 + Maven, Node and the Solana/Anchor toolchain (scripts/setup-toolchain.sh).
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PROGRAM_ID=EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r
for dir in "$HOME/.cargo/bin" "$HOME/.local/share/solana/install/active_release/bin"; do
	[ -d "$dir" ] && PATH="$dir:$PATH"
done
export PATH

PIDS=""
cleanup() {
	trap - INT TERM EXIT
	[ -n "$PIDS" ] && kill $PIDS 2>/dev/null
	wait 2>/dev/null
}
trap cleanup INT TERM EXIT

wait_for() {
	echo "Waiting for $1..."
	i=0
	until curl -s -o /dev/null "$1"; do
		i=$((i + 1))
		[ "$i" -gt 240 ] && { echo "$1 did not start" >&2; exit 1; }
		sleep 1
	done
}

[ -f "$ROOT/uptime-deal/target/deploy/uptime_deal.so" ] || (cd "$ROOT/uptime-deal" && anchor build)

mkdir -p "$ROOT/uptime-deal/.anchor"
solana-test-validator --reset --quiet --ledger "$ROOT/uptime-deal/.anchor/demo-ledger" \
	--bpf-program "$PROGRAM_ID" "$ROOT/uptime-deal/target/deploy/uptime_deal.so" &
PIDS="$PIDS $!"
wait_for http://127.0.0.1:8899/health

(cd "$ROOT/uptime-service" && exec mvn -q spring-boot:run) &
PIDS="$PIDS $!"
wait_for http://localhost:8080/api/application/state

(cd "$ROOT/uptime-monitor" && exec mvn -q spring-boot:run -Dspring-boot.run.arguments="--monitor.blockchain.rpc-url=http://127.0.0.1:8899") &
PIDS="$PIDS $!"
wait_for http://localhost:8082/actuator/health

(cd "$ROOT/frontend" && VITE_SOLANA_RPC_URL=http://127.0.0.1:8899 VITE_SOLANA_BURNER_WALLET=true exec npm run dev) &
PIDS="$PIDS $!"
wait_for http://localhost:5173

echo
echo "Ready: open http://localhost:5173/deal (Ctrl-C stops everything)."
wait
