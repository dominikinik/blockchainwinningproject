import { readFileSync } from 'node:fs'

/** The `sla` program IDL, copied here by `scripts/sync-idl.sh`. Do not edit `idl/sla.json` by hand. */
export const IDL_URL = new URL('./idl/sla.json', import.meta.url)

export interface SlaIdl {
  address: string
  metadata: { name: string; version: string }
  instructions: { name: string }[]
  accounts: { name: string }[]
  events: { name: string }[]
  errors: { code: number; name: string }[]
}

export function loadIdl(): SlaIdl {
  return JSON.parse(readFileSync(IDL_URL, 'utf8')) as SlaIdl
}
