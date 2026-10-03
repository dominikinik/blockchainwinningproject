# CLAUDE.md — sla-monitor

A monitor node for the `sla` program (TypeScript, Node 24). Each node runs with its own keypair, which an admin registers on-chain. The node finds the SLAs that list it, checks their endpoints on schedule, submits one signed `submit_report` per window, and cranks `finalize_window`. Keep this file in step with changes to the module's behavior, architecture, configuration, or commands.

## Status

Phase 0 is a scaffold. There is only the IDL loader (`src/idl.ts`) and its test. Task T2 implements the node.

## Contract

- `src/idl/sla.json` is the program IDL, copied by `../scripts/sync-idl.sh` from `sla-program/target/idl/`. Never edit it here. Re-run the script after the program changes.
- Behavior the IDL doesn't express is in [`../sla-program/SPEC.md`](../sla-program/SPEC.md):
  - window and slot times
  - bitmap bit order
  - the reporting deadline
  - memcmp offsets for finding SLAs by monitor
  - which accounts each instruction needs, including remaining accounts

## Commands

Run from `sla-monitor/`:

```bash
npm install
npm test                         # all tests once (Vitest)
npm run test:watch
npx vitest run src/idl.test.ts   # single file
npx vitest run -t "exposes"      # single test by name
npm run build                    # tsc to dist/
```

## Testing notes

Tests use Vitest with a fake clock (`vi.useFakeTimers`), a mocked `fetch`, and a mocked program client. They never touch the network or a validator, and must stay fast and deterministic.
