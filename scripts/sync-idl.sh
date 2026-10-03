#!/bin/sh
# Copies the sla program's IDL and its generated TypeScript type (the contract between modules)
# from sla-program's build output into the modules that use them. Run after `anchor build` in
# sla-program/.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
IDL="$ROOT/sla-program/target/idl/sla.json"
TYPES="$ROOT/sla-program/target/types/sla.ts"

for src in "$IDL" "$TYPES"; do
	if [ ! -f "$src" ]; then
		echo "Missing $src; run 'anchor build' in sla-program/ first." >&2
		exit 1
	fi
done

for dest in "$ROOT/frontend/src/services/solana/idl" "$ROOT/sla-monitor/src/idl"; do
	mkdir -p "$dest"
	cp "$IDL" "$dest/sla.json"
	cp "$TYPES" "$dest/sla.ts"
	echo "Copied sla.json and sla.ts to ${dest#"$ROOT"/}"
done
