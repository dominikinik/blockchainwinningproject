import { describe, expect, it } from 'vitest'
import { parseConfig } from './config.js'

const env = { KEYPAIR_PATH: '/k.json' }

describe('parseConfig', () => {
  it('applies defaults', () => {
    const c = parseConfig([], env)
    expect(c).toMatchObject({
      keypairPath: '/k.json',
      rpcUrl: 'http://localhost:8899',
      pollIntervalSecs: 10,
      stateFile: './monitor-state.json',
    })
    expect(c.programId.toBase58()).toBe('4ACuzhWwVVqtbYgicWzq11BtowhsEEHLcChJn2gVR4R9')
  })
  it('lets CLI flags override env', () => {
    const c = parseConfig(['--rpc-url', 'https://x.test', '--poll-interval=5', '--state-file', 's.json'], {
      ...env,
      RPC_URL: 'http://old',
    })
    expect(c).toMatchObject({ rpcUrl: 'https://x.test', pollIntervalSecs: 5, stateFile: 's.json' })
  })
  it('requires a keypair', () => {
    expect(() => parseConfig([], {})).toThrow(/KEYPAIR_PATH/)
  })
  it.each([['0'], ['-1'], ['1.5'], ['abc']])('rejects poll interval %s', (v) => {
    expect(() => parseConfig([], { ...env, POLL_INTERVAL_SECS: v })).toThrow(/POLL_INTERVAL_SECS/)
  })
  it('rejects a bad RPC URL and a bad program id', () => {
    expect(() => parseConfig([], { ...env, RPC_URL: 'nope' })).toThrow(/RPC_URL/)
    expect(() => parseConfig([], { ...env, RPC_URL: 'ftp://x' })).toThrow(/RPC_URL/)
    expect(() => parseConfig([], { ...env, PROGRAM_ID: 'zzz' })).toThrow(/PROGRAM_ID/)
  })
  it('rejects unknown flags and missing values', () => {
    expect(() => parseConfig(['--bogus', '1'], env)).toThrow(/unknown/)
    expect(() => parseConfig(['--rpc-url'], env)).toThrow(/needs a value/)
  })
})
