#!/bin/sh
# Runs every module's test suite and fails if any of them fails. Used by .githooks/pre-commit.
# Every module must be listed here; add new modules when you create them.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
FAILED=""

run() {
	name=$1
	shift
	echo "==> $name"
	start=$(date +%s)
	if (cd "$ROOT/$name" && "$@"); then
		echo "==> $name passed in $(($(date +%s) - start))s"
	else
		echo "==> $name FAILED" >&2
		FAILED="$FAILED $name"
	fi
}

frontend_tests() {
	[ -d node_modules ] || npm ci --no-audit --no-fund || return 1
	npm test --silent
}

run uptime-db sh test/run-tests.sh
run uptime-service ./mvnw -q test
run frontend frontend_tests

if [ -n "$FAILED" ]; then
	echo "Tests failed in:$FAILED" >&2
	exit 1
fi
echo "All module tests passed."
