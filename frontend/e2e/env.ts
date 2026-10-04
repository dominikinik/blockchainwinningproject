/** Ports of the e2e stack, kept apart from the dev defaults (8899, 8080, 5173) so both can run. */
export const RPC_URL = 'http://127.0.0.1:18899'
/** The health provider (uptime-service). */
export const PROVIDER_URL = 'http://localhost:18080'
/** The deal oracle and uptime history (uptime-monitor). */
export const MONITOR_URL = 'http://localhost:18082'
export const WEB_URL = 'http://localhost:5174'
export const PROGRAM_ID = 'EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r'
