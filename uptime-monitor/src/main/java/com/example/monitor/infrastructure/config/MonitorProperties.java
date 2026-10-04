package com.example.monitor.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param healthUrl       the provider's health endpoint that is relayed
 * @param checkIntervalMs how often the health endpoint is called and relayed
 * @param probeTimeoutMs  connect and read timeout of one health call; keep it below the interval
 * @param blockchain      where observations go
 * @param deal            the {@code uptime_deal} program
 * @param history         limits of {@code GET /api/uptime}
 */
@ConfigurationProperties("monitor")
public record MonitorProperties(String healthUrl, long checkIntervalMs, long probeTimeoutMs, Blockchain blockchain,
		Deal deal, History history) {

	/**
	 * @param enabled               {@code false} only logs health results and sends nothing
	 * @param rpcUrl                Solana JSON-RPC URL
	 * @param rpcTimeoutMs          connect and read timeout of every RPC call
	 * @param oracleKeypair         Solana keypair file that signs observations, created if missing; blank means a new
	 *                              in-memory key on every start
	 * @param oracleMinLamports     balance below which the oracle asks the faucet for more; 0 disables
	 * @param oracleAirdropLamports how much to ask the faucet for
	 */
	public record Blockchain(boolean enabled, String rpcUrl, long rpcTimeoutMs, String oracleKeypair,
			long oracleMinLamports, long oracleAirdropLamports) {
	}

	/** @param programId address of the {@code uptime_deal} program */
	public record Deal(String programId) {
	}

	/**
	 * @param defaultRangeSeconds length of the range when {@code from} is omitted
	 * @param maxRangeSeconds     longest range accepted, and how long results are kept in memory
	 */
	public record History(long defaultRangeSeconds, long maxRangeSeconds) {
	}

}
