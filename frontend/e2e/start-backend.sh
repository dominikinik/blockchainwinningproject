#!/bin/sh
# Starts the backend for the e2e tests: PostgreSQL from monitor-db (on the monitor_test database, so a
# dev monitor writing to "monitor" can run alongside), the health provider (uptime-service, no database)
# in the background, then uptime-monitor in the foreground as the deal oracle with a fresh in-memory
# key and the e2e validator. The provider stops when this script exits.
# Usage: e2e/start-backend.sh <provider port> <monitor port> <rpc url>
set -eu
cd "$(dirname "$0")/../.."
PROVIDER_PORT=$1
MONITOR_PORT=$2
RPC_URL=$3

# Reuse a running monitor-db container as is: "compose up" from another checkout or worktree would
# recreate it on that checkout's data directory.
if [ "$(docker inspect -f '{{.State.Running}}' monitor-db 2>/dev/null)" != "true" ]; then
	docker compose -f monitor-db/docker-compose.yml up -d --wait
fi
# Data directories created before monitor_test existed lack it; the schema script is idempotent.
if ! docker exec monitor-db psql -U monitor -d monitor -tAc "SELECT 1 FROM pg_database WHERE datname = 'monitor_test'" | grep -q 1; then
	docker exec monitor-db createdb -U monitor -O monitor monitor_test
fi
docker exec -e POSTGRES_USER=monitor monitor-db sh /docker-entrypoint-initdb.d/02-schema.sh
# Start every run from empty tables so old deals and history cannot leak into the tests.
docker exec monitor-db psql -U monitor -d monitor_test -c "TRUNCATE tracking_event, uptime_deal"

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
(cd uptime-monitor && exec mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=$MONITOR_PORT --monitor.blockchain.rpc-url=$RPC_URL --monitor.blockchain.oracle-keypair= --spring.datasource.url=jdbc:postgresql://localhost:5433/monitor_test --monitor.default-service.health-url=http://localhost:$PROVIDER_PORT/api/health --monitor.check-interval-ms=2000") &
MONITOR_PID=$!
wait "$MONITOR_PID"
