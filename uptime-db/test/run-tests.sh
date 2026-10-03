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
up_samples|integer||NO
partial_coverage|boolean||YES
failures|jsonb||YES'
for db in uptime uptime_test; do
	columns=$(psql "$db" -c "SELECT column_name, data_type, datetime_precision, is_nullable
		FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'uptime_record' ORDER BY ordinal_position")
	[ "$columns" = "$expected_columns" ] || fail "$db.uptime_record columns differ:
$columns"
	[ "$(psql "$db" -c "SELECT a.attname FROM pg_index i JOIN pg_attribute a
		ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
		WHERE i.indrelid = 'uptime_record'::regclass AND i.indisprimary")" = ts ] \
		|| fail "$db.uptime_record primary key must be ts"
	pass "$db.uptime_record has the expected columns and primary key"
done

# Edge cases on the test database: timezone handling, duplicate seconds, NULLs.
psql uptime_test -c "INSERT INTO uptime_record (ts, up, samples, up_samples) VALUES ('2000-01-01 02:00:00+02', true, 100, 100)"
[ "$(psql uptime_test -c "SET TIME ZONE 'UTC'; SELECT ts FROM uptime_record")" = '2000-01-01 00:00:00+00' ] \
	|| fail "ts must be stored as an absolute instant"
pass "ts is stored time-zone aware"

# Expects the statement to fail with an error containing $2.
rejects() {
	if out=$(psql uptime_test -c "$1" 2>&1); then fail "accepted: $1"; fi
	echo "$out" | grep -q "$2" || fail "unexpected error for: $1
$out"
}

rejects "INSERT INTO uptime_record (ts, up, samples, up_samples) VALUES ('2000-01-01 00:00:00Z', false, 1, 0)" 'duplicate key'
pass "duplicate second is rejected"

rejects "INSERT INTO uptime_record (ts, up, samples, up_samples) VALUES ('2000-01-01 00:00:05Z', NULL, 1, 1)" 'not-null constraint'
rejects "INSERT INTO uptime_record (ts, up, samples, up_samples) VALUES ('2000-01-01 00:00:05Z', true, NULL, 1)" 'not-null constraint'
rejects "INSERT INTO uptime_record (ts, up, samples, up_samples) VALUES ('2000-01-01 00:00:05Z', true, 1, NULL)" 'not-null constraint'
rejects "INSERT INTO uptime_record (ts, up, samples, up_samples) VALUES (NULL, true, 1, 1)" 'not-null constraint'
pass "NULL values are rejected"

rejects "INSERT INTO uptime_record VALUES ('2000-01-01 00:00:05Z', true, 1, 1, true, NULL)" 'check constraint'
rejects "INSERT INTO uptime_record VALUES ('2000-01-01 00:00:05Z', true, 1, 1, NULL, '[]')" 'check constraint'
rejects "INSERT INTO uptime_record VALUES ('2000-01-01 00:00:05Z', true, 1, 1, false, '{}')" 'check constraint'
psql uptime_test -c "INSERT INTO uptime_record VALUES ('2000-01-01 00:00:05Z', true, 1, 1, false, '[]')"
pass "legacy details must be paired and failures must be an array"

# Exact tracking storage shapes, including lossless TEXT instants and JSONB payloads.
expected_tracking_columns='bad_event|id|uuid|NO
bad_event|uptime_event_id|uuid|NO
bad_event|session_id|uuid|NO
bad_event|ordinal|integer|NO
bad_event|payload|jsonb|NO
tracking_event|id|uuid|NO
tracking_event|session_id|uuid|NO
tracking_event|type|text|NO
tracking_event|payload|jsonb|NO
tracking_session|id|uuid|NO
tracking_session|started_at|text|NO
tracking_session|stopped_at|text|YES
tracking_session|status|text|NO
tracking_session|committed_through|text|YES
tracking_session|start_second|bigint|NO
tracking_session|stop_second|bigint|YES
uptime_event|id|uuid|NO
uptime_event|session_id|uuid|NO
uptime_event|bucket_start|text|NO
uptime_event|start_second|bigint|NO
uptime_event|end_second|bigint|NO
uptime_event|payload|jsonb|NO'
check_tracking_schema() {
	columns=$(psql "$db" -c "SELECT table_name, column_name, data_type, is_nullable
		FROM information_schema.columns WHERE table_schema='public'
		AND table_name IN ('tracking_session','tracking_event','uptime_event','bad_event')
		ORDER BY table_name, ordinal_position")
	[ "$columns" = "$expected_tracking_columns" ] || fail "$db tracking columns differ:
$columns"
	for table in tracking_session tracking_event uptime_event bad_event; do
		[ "$(psql "$db" -c "SELECT a.attname FROM pg_index i JOIN pg_attribute a
			ON a.attrelid=i.indrelid AND a.attnum=ANY(i.indkey)
			WHERE i.indrelid='$table'::regclass AND i.indisprimary")" = id ] \
			|| fail "$db.$table primary key must be id"
	done
}
# Capture complete schema metadata for comparing fresh init and migration output.
schema_signature() {
	psql "$db" -c "SELECT table_name, column_name, data_type, datetime_precision, is_nullable, column_default
		FROM information_schema.columns WHERE table_schema='public' ORDER BY table_name, ordinal_position;
		SELECT c.relname, con.conname, pg_get_constraintdef(con.oid)
		FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
		JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'
		ORDER BY c.relname, con.conname;
		SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname='public'
		ORDER BY tablename, indexname"
}
for db in uptime uptime_test; do
	check_tracking_schema
	pass "$db tracking tables have exact columns and primary keys"
done

# Validate constraints, not merely the existence of tables/indexes.
s1=00000000-0000-0000-0000-000000000001
s2=00000000-0000-0000-0000-000000000002
parent=00000000-0000-0000-0000-000000000010
other=00000000-0000-0000-0000-000000000099
psql uptime_test -c "INSERT INTO tracking_session (id,started_at,status,start_second)
	VALUES ('$s1','2000-01-01T00:00:00.123456789Z','ACTIVE',946684800)"
rejects "INSERT INTO tracking_session (id,started_at,status,start_second) VALUES ('$s2','t','ACTIVE',0)" 'duplicate key'
rejects "INSERT INTO tracking_session (id,started_at,status,start_second) VALUES ('$s2','t','INVALID',0)" 'check constraint'
rejects "INSERT INTO tracking_session (id,started_at,status,start_second) VALUES ('$s2','t','STOPPED',0)" 'check constraint'
rejects "UPDATE tracking_session SET stopped_at='t' WHERE id='$s1'" 'check constraint'
psql uptime_test -c "UPDATE tracking_session SET status='STOPPING', stopped_at='2000-01-01T00:00:01.123456789Z', stop_second=946684801 WHERE id='$s1'"
rejects "INSERT INTO tracking_session (id,started_at,status,start_second) VALUES ('$s2','t','ACTIVE',0)" 'duplicate key'
psql uptime_test -c "UPDATE tracking_session SET status='STOPPED' WHERE id='$s1';
	INSERT INTO tracking_session (id,started_at,status,start_second) VALUES ('$s2','t','ACTIVE',0)"
[ "$(psql uptime_test -c "SELECT started_at FROM tracking_session WHERE id='$s1'")" = '2000-01-01T00:00:00.123456789Z' ] \
	|| fail "tracking instants lost nanoseconds"
pass "session status/bounds and single ACTIVE or STOPPING session are enforced"

psql uptime_test -c "INSERT INTO tracking_event VALUES ('00000000-0000-0000-0000-000000000020','$s1','START','{}')"
rejects "INSERT INTO tracking_event VALUES ('$other','$s1','START','{}')" 'duplicate key'
rejects "INSERT INTO tracking_event VALUES ('$other','$s1','INVALID','{}')" 'check constraint'
rejects "INSERT INTO tracking_event VALUES ('$other','$s1','STOP','[]')" 'check constraint'
rejects "INSERT INTO tracking_event VALUES ('$other','$other','STOP','{}')" 'foreign key constraint'
rejects "INSERT INTO tracking_event VALUES ('$other','$s1','STOP',NULL)" 'not-null constraint'
pass "lifecycle event type, uniqueness, payload and session FK are enforced"

psql uptime_test -c "INSERT INTO uptime_event VALUES ('$parent','$s1','2000-01-01T00:00:00Z',946684800,946684801,'{}')"
rejects "INSERT INTO uptime_event VALUES ('$other','$s1','2000-01-01T00:00:00Z',0,1,'{}')" 'duplicate key'
rejects "INSERT INTO uptime_event VALUES ('$other','$other','t',0,1,'{}')" 'foreign key constraint'
rejects "INSERT INTO uptime_event VALUES ('$other','$s1','t',0,1,'[]')" 'check constraint'
rejects "INSERT INTO uptime_event VALUES ('$other','$s1','t',0,1,NULL)" 'not-null constraint'
# Same bucket in another session must remain legal.
psql uptime_test -c "INSERT INTO uptime_event VALUES ('00000000-0000-0000-0000-000000000011','$s2','2000-01-01T00:00:00Z',0,1,'{}');
	INSERT INTO bad_event VALUES ('00000000-0000-0000-0000-000000000030','$parent','$s1',0,'{}')"
rejects "INSERT INTO bad_event VALUES ('$other','$parent','$s1',0,'{}')" 'duplicate key'
rejects "INSERT INTO bad_event VALUES ('$other','$parent','$s1',-1,'{}')" 'check constraint'
rejects "INSERT INTO bad_event VALUES ('$other','$parent','$s2',1,'{}')" 'foreign key constraint'
rejects "INSERT INTO bad_event VALUES ('$other','$other','$s1',1,'{}')" 'foreign key constraint'
rejects "INSERT INTO bad_event VALUES ('$other','$parent','$s1',1,'[]')" 'check constraint'
rejects "INSERT INTO bad_event VALUES ('$other','$parent','$s1',1,NULL)" 'not-null constraint'
rejects "DELETE FROM uptime_event WHERE id='$parent'" 'foreign key constraint'
pass "parent bucket uniqueness, JSON payloads, composite child FK and ordinals are enforced"

# Recreate ONLY schemas in the disposable container to exercise real upgrades.
# A four-column historical fixture must survive 001/002 without invented sessions.
for db in uptime uptime_test; do
	fresh_schema=$(schema_signature)
	psql "$db" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;
		CREATE TABLE uptime_record (ts TIMESTAMP(6) WITH TIME ZONE PRIMARY KEY,
		up BOOLEAN NOT NULL, samples INTEGER NOT NULL, up_samples INTEGER NOT NULL);
		INSERT INTO uptime_record VALUES ('2000-01-01 02:00:00.123456+02',false,100,42)"
	for migration in 001-uptime-event-details.sql 002-tracking-and-bad-events.sql; do
		docker cp "$MODULE_DIR/migrations/$migration" "$CONTAINER:/tmp/$migration"
		psql "$db" -f "/tmp/$migration"
	done
	[ "$(schema_signature)" = "$fresh_schema" ] || fail "$db migrated schema differs from fresh init"
	check_tracking_schema
	[ "$(psql "$db" -c "SET TIME ZONE 'UTC'; SELECT ts,up,samples,up_samples,
		partial_coverage IS NULL, failures IS NULL FROM uptime_record")" = '2000-01-01 00:00:00.123456+00|f|100|42|t|t' ] \
		|| fail "$db migration changed legacy data or backfilled details"
	[ "$(psql "$db" -c "SELECT (SELECT count(*) FROM tracking_session)+(SELECT count(*) FROM tracking_event)
		+(SELECT count(*) FROM uptime_event)+(SELECT count(*) FROM bad_event)")" = 0 ] \
		|| fail "$db migration fabricated tracking history"
	psql "$db" -c "INSERT INTO tracking_session (id,started_at,stopped_at,status,start_second,stop_second)
		VALUES ('$s1','2000-01-01T00:00:00.123456789Z','2000-01-01T00:00:01.123456789Z','STOPPED',946684800,946684801);
		INSERT INTO tracking_event VALUES ('00000000-0000-0000-0000-000000000020','$s1','START','{}');
		INSERT INTO uptime_event VALUES ('$parent','$s1','2000-01-01T00:00:00Z',946684800,946684801,'{}');
		INSERT INTO bad_event VALUES ('00000000-0000-0000-0000-000000000030','$parent','$s1',0,'{}')"
	before=$(psql "$db" -c "SELECT row_to_json(r) FROM uptime_record r;
		SELECT row_to_json(r) FROM tracking_session r; SELECT row_to_json(r) FROM tracking_event r;
		SELECT row_to_json(r) FROM uptime_event r; SELECT row_to_json(r) FROM bad_event r")
	for migration in 001-uptime-event-details.sql 002-tracking-and-bad-events.sql; do
		psql "$db" -f "/tmp/$migration"
	done
	[ "$(schema_signature)" = "$fresh_schema" ] || fail "$db migration rerun changed schema"
	after=$(psql "$db" -c "SELECT row_to_json(r) FROM uptime_record r;
		SELECT row_to_json(r) FROM tracking_session r; SELECT row_to_json(r) FROM tracking_event r;
		SELECT row_to_json(r) FROM uptime_event r; SELECT row_to_json(r) FROM bad_event r")
	[ "$before" = "$after" ] || fail "$db migration rerun changed legacy or tracked history"
	pass "$db migrations match fresh init, preserve historical rows, and are rerunnable"
done

echo "uptime-db: $PASSED checks passed"
