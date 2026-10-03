#!/bin/sh
# Runs every module's test suite and fails if any of them fails. Used by .githooks/pre-commit.
# Every module must be listed here; add new modules when you create them.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
FAILED=""

# Git hooks run without the login shell's PATH; add the toolchain dirs scripts/setup-toolchain.sh uses.
for dir in "$HOME/.local/bin" "$HOME/.cargo/bin" "$HOME/.local/share/solana/install/active_release/bin"; do
	[ -d "$dir" ] && PATH="$dir:$PATH"
done
export PATH

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

# LiteSVM tests load target/deploy/uptime_deal.so, so build before testing.
uptime_deal_tests() {
	anchor build && cargo test
}

run uptime-db sh test/run-tests.sh
run monitor-db sh test/run-tests.sh
run uptime-service mvn -q test
run uptime-monitor mvn -q test
run frontend frontend_tests
run uptime-deal uptime_deal_tests

if [ -n "$FAILED" ]; then
	echo "Tests failed in:$FAILED" >&2
	exit 1
fi
echo "All module tests passed."
