package com.example.monitor.infrastructure.config;

import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param checkIntervalMs how often every tracked service's health endpoint is called
 * @param probeTimeoutMs  connect and read timeout of one health call; keep it below the interval
 * @param blockchain      where downtime reports and deal settlements go
 * @param deal            how deals are settled
 * @param defaultService  the service tracked from startup, which deals are linked to by default
 * @param history         limits of {@code GET /api/uptime}
 */
@ConfigurationProperties("monitor")
public record MonitorProperties(long checkIntervalMs, long probeTimeoutMs, Blockchain blockchain, Deal deal,
		DefaultService defaultService, History history) {

	/**
	 * @param enabled               {@code false} only logs reports and turns the deal oracle off
	 * @param rpcUrl                Solana JSON-RPC URL
	 * @param rpcTimeoutMs          connect and read timeout of every RPC call
	 * @param oracleKeypair         Solana keypair file that signs memos and settlements, created if missing;
	 *                              blank means a new in-memory key on every start
	 * @param oracleMinLamports     balance below which the oracle asks the faucet for more; 0 disables
	 * @param oracleAirdropLamports how much to ask the faucet for
	 */
	public record Blockchain(boolean enabled, String rpcUrl, long rpcTimeoutMs, String oracleKeypair,
			long oracleMinLamports, long oracleAirdropLamports) {
	}

	/**
	 * @param programId             address of the {@code uptime_deal} program
	 * @param maxDurationSeconds    longest on-chain window a deal may have to be registered
	 * @param settleGraceSeconds    how long after a window ends before it is settled
	 * @param pollIntervalMs        how often deals are advanced (window ends, sends, confirmations)
	 * @param maxSettleAttempts     failed sends after which a deal is {@code FAILED}
	 * @param confirmTimeoutSeconds how long to wait for a sent settlement before sending it again
	 */
	public record Deal(String programId, long maxDurationSeconds, long settleGraceSeconds, long pollIntervalMs,
			int maxSettleAttempts, long confirmTimeoutSeconds) {
	}

	/**
	 * @param id        UUID the default service is tracked under
	 * @param healthUrl its health endpoint; blank tracks nothing at startup and leaves deals without a default
	 */
	public record DefaultService(UUID id, String healthUrl) {

		public boolean enabled() {
			return id != null && healthUrl != null && !healthUrl.isBlank();
		}

	}

	/**
	 * @param defaultRangeSeconds length of the range when {@code from} is omitted
	 * @param maxRangeSeconds     longest range accepted
	 */
	public record History(long defaultRangeSeconds, long maxRangeSeconds) {
	}

}
