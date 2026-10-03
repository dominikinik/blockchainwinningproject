#!/bin/sh
# Starts the full uptime-deal stack for manual testing in the browser, and stops it on Ctrl-C:
#   - solana-test-validator on :8899 with the uptime_deal program (fresh ledger each run)
#   - uptime-service on :8080 (PostgreSQL from uptime-db for history), the deal monitor (oracle) that
#     records per-round observations on chain; the program decides the outcome
#   - the frontend on :5173 with the in-browser burner wallet
# Then open http://localhost:5173/deal, connect "Burner Wallet", press "Airdrop 2 SOL" and create a deal.
# Needs Docker, Java 21, Node and the Solana/Anchor toolchain (scripts/setup-toolchain.sh).
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

# Reuse a running uptime-db container: "compose up" from another checkout would recreate it on this one's data.
if [ "$(docker inspect -f '{{.State.Running}}' uptime-db 2>/dev/null)" != "true" ]; then
	docker compose -f "$ROOT/uptime-db/docker-compose.yml" up -d --wait
fi
(cd "$ROOT/uptime-service" && exec ./mvnw -q spring-boot:run) &
PIDS="$PIDS $!"
wait_for http://localhost:8080/api/application/state

(cd "$ROOT/frontend" && VITE_SOLANA_RPC_URL=http://127.0.0.1:8899 VITE_SOLANA_BURNER_WALLET=true exec npm run dev) &
PIDS="$PIDS $!"
wait_for http://localhost:5173

echo
echo "Ready: open http://localhost:5173/deal (Ctrl-C stops everything)."
wait
