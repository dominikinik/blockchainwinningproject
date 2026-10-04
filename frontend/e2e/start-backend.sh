#!/bin/sh
# Starts the backend for the e2e tests: the health provider (uptime-service, no database) in the
# background, then uptime-monitor (a stateless proxy, no database) in the foreground as the deal oracle
# with a fresh in-memory key and the e2e validator. The monitor reports rounds to every active deal
# that names its key; it never settles, so the tests settle through the page. The provider stops when
# this script exits.
# Usage: e2e/start-backend.sh <provider port> <monitor port> <rpc url>
set -eu
cd "$(dirname "$0")/../.."
PROVIDER_PORT=$1
MONITOR_PORT=$2
RPC_URL=$3

PROVIDER_PID=""
MONITOR_PID=""
cleanup() {
	trap - INT TERM EXIT
	[ -n "$MONITOR_PID" ] && kill "$MONITOR_PID" 2>/dev/null
	[ -n "$PROVIDER_PID" ] && kill "$PROVIDER_PID" 2>/dev/null
	return 0
}
trap cleanup INT TERM EXIT

(cd uptime-service && exec mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=$PROVIDER_PORT") &
PROVIDER_PID=$!

i=0
until curl -s -o /dev/null "http://localhost:$PROVIDER_PORT/api/application/state"; do
	i=$((i + 1))
	[ "$i" -gt 240 ] && { echo "provider did not start" >&2; exit 1; }
	sleep 1
done

# Run the monitor in the background and wait, so the trap can stop both services on SIGTERM.
(cd uptime-monitor && exec mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=$MONITOR_PORT --monitor.blockchain.rpc-url=$RPC_URL --monitor.blockchain.oracle-keypair= --monitor.health-url=http://localhost:$PROVIDER_PORT/api/health --monitor.check-interval-ms=2000") &
MONITOR_PID=$!
wait "$MONITOR_PID"
