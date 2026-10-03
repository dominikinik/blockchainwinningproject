#!/bin/sh
# Tests for the uptime-db module. Static checks first, then a throwaway postgres:17-alpine container
# (no published port, tmpfs data) runs init/ exactly as compose would, and the resulting schema is
# checked. The real uptime-db container and its data/ directory are never touched.
set -eu

MODULE_DIR=$(cd "$(dirname "$0")/.." && pwd)
CONTAINER="uptime-db-test-$$"
PASSED=0

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { PASSED=$((PASSED + 1)); echo "ok   $*"; }

# --- static checks --------------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || fail "docker is required to test uptime-db"
docker info >/dev/null 2>&1 || fail "docker daemon is not running"

docker compose -f "$MODULE_DIR/docker-compose.yml" config -q || fail "docker-compose.yml is invalid"
pass "docker-compose.yml is valid"

config=$(docker compose -f "$MODULE_DIR/docker-compose.yml" config)
for expected in 'image: postgres:17-alpine' 'POSTGRES_USER: uptime' 'POSTGRES_PASSWORD: uptime' \
	'POSTGRES_DB: uptime' 'published: "5432"' 'target: /docker-entrypoint-initdb.d' 'read_only: true'; do
	echo "$config" | grep -qF "$expected" || fail "compose config is missing '$expected'"
done
pass "compose defines image, credentials, port 5432 and read-only init mount"

sh -n "$MODULE_DIR/init/02-schema.sh" || fail "init/02-schema.sh has a syntax error"
pass "init/02-schema.sh parses"

[ "$(ls "$MODULE_DIR/init")" = "$(printf '01-databases.sql\n02-schema.sh')" ] \
	|| fail "init/ must contain exactly 01-databases.sql and 02-schema.sh (run order matters)"
pass "init scripts present in run order"

# --- container checks -----------------------------------------------------------------------------
cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

docker run -d --name "$CONTAINER" --tmpfs /var/lib/postgresql/data \
	-e POSTGRES_USER=uptime -e POSTGRES_PASSWORD=uptime -e POSTGRES_DB=uptime \
	-v "$MODULE_DIR/init:/docker-entrypoint-initdb.d:ro" postgres:17-alpine >/dev/null

# The entrypoint runs init/ on a temporary socket-only server, then restarts on TCP. Wait for that.
i=0
until docker logs "$CONTAINER" 2>&1 | grep -q 'init process complete' \
	&& docker exec "$CONTAINER" pg_isready -h 127.0.0.1 -U uptime -d uptime >/dev/null 2>&1; do
	i=$((i + 1))
	[ "$i" -le 60 ] || { docker logs "$CONTAINER" >&2; fail "database did not start within 30s"; }
	if ! docker ps -q --filter "name=^$CONTAINER\$" | grep -q .; then
		docker logs "$CONTAINER" >&2; fail "container exited during init"
	fi
	sleep 0.5
done
pass "database starts and init scripts complete"

psql() { db=$1; shift; docker exec "$CONTAINER" psql -v ON_ERROR_STOP=1 -U uptime -d "$db" -tAq "$@"; }

[ "$(psql uptime -c "SELECT count(*) FROM pg_database WHERE datname IN ('uptime','uptime_test')")" = 2 ] \
	|| fail "databases uptime and uptime_test must both exist"
[ "$(psql uptime -c "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='uptime_test'")" = uptime ] \
	|| fail "uptime_test must be owned by uptime"
pass "uptime and uptime_test databases exist, owned by uptime"

expected_columns='ts|timestamp with time zone|6|NO
up|boolean||NO
samples|integer||NO
up_samples|integer||NO'
for db in uptime uptime_test; do
	columns=$(psql "$db" -c "SELECT column_name, data_type, datetime_precision, is_nullable
		FROM information_schema.columns WHERE table_name = 'uptime_record' ORDER BY ordinal_position")
	[ "$columns" = "$expected_columns" ] || fail "$db.uptime_record columns differ:
$columns"
	[ "$(psql "$db" -c "SELECT a.attname FROM pg_index i JOIN pg_attribute a
		ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
		WHERE i.indrelid = 'uptime_record'::regclass AND i.indisprimary")" = ts ] \
		|| fail "$db.uptime_record primary key must be ts"
	pass "$db.uptime_record has the expected columns and primary key"
done

# Edge cases on the test database: timezone handling, duplicate seconds, NULLs.
psql uptime_test -c "INSERT INTO uptime_record VALUES ('2000-01-01 02:00:00+02', true, 100, 100)"
[ "$(psql uptime_test -c "SET TIME ZONE 'UTC'; SELECT ts FROM uptime_record")" = '2000-01-01 00:00:00+00' ] \
	|| fail "ts must be stored as an absolute instant"
pass "ts is stored time-zone aware"

# Expects the statement to fail with an error containing $2.
rejects() {
	if out=$(psql uptime_test -c "$1" 2>&1); then fail "accepted: $1"; fi
	echo "$out" | grep -q "$2" || fail "unexpected error for: $1
$out"
}

rejects "INSERT INTO uptime_record VALUES ('2000-01-01 00:00:00Z', false, 1, 0)" 'duplicate key'
pass "duplicate second is rejected"

rejects "INSERT INTO uptime_record VALUES ('2000-01-01 00:00:05Z', NULL, 1, 1)" 'not-null constraint'
rejects "INSERT INTO uptime_record VALUES ('2000-01-01 00:00:05Z', true, NULL, 1)" 'not-null constraint'
rejects "INSERT INTO uptime_record VALUES ('2000-01-01 00:00:05Z', true, 1, NULL)" 'not-null constraint'
rejects "INSERT INTO uptime_record VALUES (NULL, true, 1, 1)" 'not-null constraint'
pass "NULL values are rejected"

echo "uptime-db: $PASSED checks passed"
