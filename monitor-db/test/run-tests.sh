#!/bin/sh
# Tests for the monitor-db module. Static checks first, then a throwaway postgres:17-alpine container
# (no published port, tmpfs data) runs init/ exactly as compose would, and the resulting schema is
# checked. The real monitor-db container and its data/ directory are never touched.
set -eu

MODULE_DIR=$(cd "$(dirname "$0")/.." && pwd)
CONTAINER="monitor-db-test-$$"
PASSED=0

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { PASSED=$((PASSED + 1)); echo "ok   $*"; }

# --- static checks --------------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || fail "docker is required to test monitor-db"
docker info >/dev/null 2>&1 || fail "docker daemon is not running"

docker compose -f "$MODULE_DIR/docker-compose.yml" config -q || fail "docker-compose.yml is invalid"
pass "docker-compose.yml is valid"

config=$(docker compose -f "$MODULE_DIR/docker-compose.yml" config)
for expected in 'image: postgres:17-alpine' 'POSTGRES_USER: monitor' 'POSTGRES_PASSWORD: monitor' \
	'POSTGRES_DB: monitor' 'published: "5433"' 'target: /docker-entrypoint-initdb.d' 'read_only: true'; do
	echo "$config" | grep -qF "$expected" || fail "compose config is missing '$expected'"
done
pass "compose defines image, credentials, port 5433 and read-only init mount"

sh -n "$MODULE_DIR/init/02-schema.sh" || fail "init/02-schema.sh has a syntax error"
pass "init/02-schema.sh parses"

[ "$(ls "$MODULE_DIR/init")" = "$(printf '01-databases.sql\n02-schema.sh')" ] \
	|| fail "init/ must contain exactly 01-databases.sql and 02-schema.sh (run order matters)"
pass "init scripts present in run order"

# --- container checks -----------------------------------------------------------------------------
cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

docker run -d --name "$CONTAINER" --tmpfs /var/lib/postgresql/data \
	-e POSTGRES_USER=monitor -e POSTGRES_PASSWORD=monitor -e POSTGRES_DB=monitor \
	-v "$MODULE_DIR/init:/docker-entrypoint-initdb.d:ro" postgres:17-alpine >/dev/null

# The entrypoint runs init/ on a temporary socket-only server, then restarts on TCP. Wait for that.
i=0
until docker logs "$CONTAINER" 2>&1 | grep -q 'init process complete' \
	&& docker exec "$CONTAINER" pg_isready -h 127.0.0.1 -U monitor -d monitor >/dev/null 2>&1; do
	i=$((i + 1))
	[ "$i" -le 60 ] || { docker logs "$CONTAINER" >&2; fail "database did not start within 30s"; }
	if ! docker ps -q --filter "name=^$CONTAINER\$" | grep -q .; then
		docker logs "$CONTAINER" >&2; fail "container exited during init"
	fi
	sleep 0.5
done
pass "database starts and init scripts complete"

psql() { db=$1; shift; docker exec "$CONTAINER" psql -v ON_ERROR_STOP=1 -U monitor -d "$db" -tAq "$@"; }

[ "$(psql monitor -c "SELECT count(*) FROM pg_database WHERE datname IN ('monitor','monitor_test')")" = 2 ] \
	|| fail "databases monitor and monitor_test must both exist"
[ "$(psql monitor -c "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='monitor_test'")" = monitor ] \
	|| fail "monitor_test must be owned by monitor"
pass "monitor and monitor_test databases exist, owned by monitor"

expected_columns='id|bigint||NO
service_id|uuid||NO
version|bigint||NO
type|character varying||NO
occurred_at|timestamp with time zone|6|NO
health_url|text||YES
check_interval_ms|bigint||YES
http_status|integer||YES
reason|text||YES'
for db in monitor monitor_test; do
	columns=$(psql "$db" -c "SELECT column_name, data_type, datetime_precision, is_nullable
		FROM information_schema.columns WHERE table_name = 'tracking_event' ORDER BY ordinal_position")
	[ "$columns" = "$expected_columns" ] || fail "$db.tracking_event columns differ:
$columns"
	[ "$(psql "$db" -c "SELECT string_agg(a.attname, ',' ORDER BY array_position(i.indkey, a.attnum))
		FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
		WHERE i.indrelid = 'tracking_event'::regclass AND i.indisprimary")" = service_id,version ] \
		|| fail "$db.tracking_event primary key must be (service_id, version)"
	pass "$db.tracking_event has the expected columns and primary key"
done

expected_deal_columns='address|character varying||NO
service_id|uuid||NO
payer|character varying||NO
recipient|character varying||NO
amount_lamports|bigint||NO
guarantee_lamports|bigint||NO
duration_seconds|bigint||NO
accept_deadline|timestamp with time zone|6|NO
starts_at|timestamp with time zone|6|YES
status|character varying||NO
up_checks|bigint||YES
total_rounds|bigint||YES
paid_to_recipient|boolean||YES
signature|character varying||YES
sent_at|timestamp with time zone|6|YES
attempts|integer||NO
error|text||YES
registered_at|timestamp with time zone|6|NO'
for db in monitor monitor_test; do
	columns=$(psql "$db" -c "SELECT column_name, data_type, datetime_precision, is_nullable
		FROM information_schema.columns WHERE table_name = 'uptime_deal' ORDER BY ordinal_position")
	[ "$columns" = "$expected_deal_columns" ] || fail "$db.uptime_deal columns differ:
$columns"
	[ "$(psql "$db" -c "SELECT a.attname FROM pg_index i JOIN pg_attribute a
		ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
		WHERE i.indrelid = 'uptime_deal'::regclass AND i.indisprimary")" = address ] \
		|| fail "$db.uptime_deal primary key must be address"
	pass "$db.uptime_deal has the expected columns and primary key"
done

# Edge cases on the test database.
S=11111111-1111-1111-1111-111111111111
ins() { echo "INSERT INTO tracking_event (service_id, version, type, occurred_at, health_url, check_interval_ms, http_status, reason) VALUES ($1)"; }

psql monitor_test -c "$(ins "'$S', 1, 'TrackingStarted', '2000-01-01 02:00:00+02', 'http://x/health', 2000, NULL, NULL")"
psql monitor_test -c "$(ins "'$S', 2, 'Downtime', '2000-01-01 00:00:02Z', NULL, NULL, 404, 'HTTP 404'")"
[ "$(psql monitor_test -c "SET TIME ZONE 'UTC'; SELECT occurred_at FROM tracking_event WHERE version = 1")" \
	= '2000-01-01 00:00:00+00' ] || fail "occurred_at must be stored as an absolute instant"
pass "occurred_at is stored time-zone aware"
[ "$(psql monitor_test -c "SELECT string_agg(id::text, ',' ORDER BY version) FROM tracking_event")" = 1,2 ] \
	|| fail "id must be generated in insertion order"
pass "id is generated in insertion order"

# Expects the statement to fail with an error containing $2.
rejects() {
	if out=$(psql monitor_test -c "$1" 2>&1); then fail "accepted: $1"; fi
	echo "$out" | grep -q "$2" || fail "unexpected error for: $1
$out"
}

rejects "$(ins "'$S', 2, 'TrackingFinished', '2000-01-01 00:00:04Z', NULL, NULL, NULL, NULL")" 'duplicate key'
pass "a second event with the same version is rejected"

rejects "$(ins "'$S', 3, 'Unknown', '2000-01-01 00:00:04Z', NULL, NULL, NULL, NULL")" 'check constraint'
rejects "$(ins "'$S', 0, 'TrackingFinished', '2000-01-01 00:00:04Z', NULL, NULL, NULL, NULL")" 'check constraint'
pass "unknown event types and versions below 1 are rejected"

rejects "$(ins "NULL, 3, 'TrackingFinished', '2000-01-01 00:00:04Z', NULL, NULL, NULL, NULL")" 'not-null constraint'
rejects "$(ins "'$S', NULL, 'TrackingFinished', '2000-01-01 00:00:04Z', NULL, NULL, NULL, NULL")" 'not-null constraint'
rejects "$(ins "'$S', 3, NULL, '2000-01-01 00:00:04Z', NULL, NULL, NULL, NULL")" 'not-null constraint'
rejects "$(ins "'$S', 3, 'TrackingFinished', NULL, NULL, NULL, NULL, NULL")" 'not-null constraint'
pass "NULL service_id, version, type and occurred_at are rejected"

rejects "INSERT INTO tracking_event (id, service_id, version, type, occurred_at) VALUES (99, '$S', 3, 'TrackingFinished', now())" 'GENERATED ALWAYS'
pass "id cannot be set by hand"

D=Dea1AddressDea1AddressDea1AddressDea1Addre
deal() { echo "INSERT INTO uptime_deal (address, service_id, payer, recipient, amount_lamports, guarantee_lamports, accept_deadline, starts_at, duration_seconds, status, registered_at) VALUES ($1)"; }
psql monitor_test -c "$(deal "'$D', '$S', 'P', 'R', 1000000, 7000000, now(), NULL, 10, 'PROPOSED', now()")"
[ "$(psql monitor_test -c "SELECT attempts FROM uptime_deal WHERE address = '$D'")" = 0 ] \
	|| fail "attempts must default to 0"
pass "a proposal row without a window start is accepted and attempts defaults to 0"
rejects "$(deal "'$D', '$S', 'P', 'R', 1000000, 7000000, now(), now(), 10, 'ACTIVE', now()")" 'duplicate key'
pass "a second deal with the same address is rejected"
rejects "$(deal "'x1', '$S', 'P', 'R', 1000000, 0, now(), now(), 10, 'PENDING', now()")" 'check constraint'
rejects "$(deal "'x2', '$S', 'P', 'R', 1000000, 0, now(), now(), 0, 'ACTIVE', now()")" 'check constraint'
rejects "$(deal "'x3', '$S', 'P', 'R', -1, 0, now(), now(), 10, 'ACTIVE', now()")" 'check constraint'
rejects "$(deal "'x6', '$S', 'P', 'R', 1000000, -1, now(), now(), 10, 'ACTIVE', now()")" 'check constraint'
pass "unknown deal statuses, zero durations and negative amounts or guarantees are rejected"
rejects "$(deal "'x4', NULL, 'P', 'R', 1000000, 0, now(), now(), 10, 'ACTIVE', now()")" 'not-null constraint'
rejects "$(deal "'x5', '$S', 'P', 'R', 1000000, 0, NULL, now(), 10, 'ACTIVE', now()")" 'not-null constraint'
pass "deals without a service or an accept deadline are rejected"

echo "monitor-db: $PASSED checks passed"
