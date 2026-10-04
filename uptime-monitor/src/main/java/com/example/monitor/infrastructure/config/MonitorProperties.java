package com.example.monitor.infrastructure.config;

import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param serviceId       UUID the relayed service is known by; deals are linked to it
 * @param healthUrl       the provider's health endpoint that is relayed
 * @param checkIntervalMs how often the health endpoint is called and relayed
 * @param probeTimeoutMs  connect and read timeout of one health call; keep it below the interval
 * @param blockchain      where observations and settlements go
 * @param deal            how deals are settled
 * @param history         limits of {@code GET /api/uptime}
 */
@ConfigurationProperties("monitor")
public record MonitorProperties(UUID serviceId, String healthUrl, long checkIntervalMs, long probeTimeoutMs,
		Blockchain blockchain, Deal deal, History history) {

	/**
	 * @param enabled               {@code false} only logs health results and turns the deal oracle off
	 * @param rpcUrl                Solana JSON-RPC URL
	 * @param rpcTimeoutMs          connect and read timeout of every RPC call
	 * @param oracleKeypair         Solana keypair file that signs observations and settlements, created if missing;
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
	 * @param pollIntervalMs        how often deals are advanced (acceptance, window ends, sends, confirmations)
	 * @param maxSettleAttempts     failed sends after which a deal is {@code FAILED}
	 * @param confirmTimeoutSeconds how long to wait for a sent settlement before sending it again
	 */
	public record Deal(String programId, long maxDurationSeconds, long settleGraceSeconds, long pollIntervalMs,
			int maxSettleAttempts, long confirmTimeoutSeconds) {
	}

	/**
	 * @param defaultRangeSeconds length of the range when {@code from} is omitted
	 * @param maxRangeSeconds     longest range accepted, and how long results are kept in memory
	 */
	public record History(long defaultRangeSeconds, long maxRangeSeconds) {
	}

}
