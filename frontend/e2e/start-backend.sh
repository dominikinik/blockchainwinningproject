#!/bin/sh
# Starts the uptime service for the e2e tests: PostgreSQL from uptime-db (on the uptime_test database,
# so a dev instance writing to "uptime" can run alongside), a fresh in-memory oracle key, and the
# e2e validator. Usage: e2e/start-backend.sh <server port> <rpc url>
set -eu
cd "$(dirname "$0")/../.."

# Reuse a running uptime-db container as is: "compose up" from another checkout or worktree would
# recreate it on that checkout's data directory.
if [ "$(docker inspect -f '{{.State.Running}}' uptime-db 2>/dev/null)" != "true" ]; then
	docker compose -f uptime-db/docker-compose.yml up -d --wait
fi
# Data directories created before uptime_test existed lack it; the init scripts are idempotent otherwise.
if ! docker exec uptime-db psql -U uptime -d uptime -tAc "SELECT 1 FROM pg_database WHERE datname = 'uptime_test'" | grep -q 1; then
	docker exec uptime-db createdb -U uptime -O uptime uptime_test
fi
docker exec -e POSTGRES_USER=uptime uptime-db sh /docker-entrypoint-initdb.d/02-schema.sh

cd uptime-service
exec ./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--server.port=$1 --deal.rpc-url=$2 --deal.oracle-keypair= --spring.datasource.url=jdbc:postgresql://localhost:5432/uptime_test"
