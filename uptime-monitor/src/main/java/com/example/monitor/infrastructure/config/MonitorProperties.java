package com.example.monitor.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param checkIntervalMs how often every tracked service's health endpoint is called
 * @param probeTimeoutMs  connect and read timeout of one health call; keep it below the interval
 * @param blockchain      where downtime reports go
 */
@ConfigurationProperties("monitor")
public record MonitorProperties(long checkIntervalMs, long probeTimeoutMs, Blockchain blockchain) {

	/**
	 * @param enabled               {@code false} only logs reports instead of sending them
	 * @param rpcUrl                Solana JSON-RPC URL
	 * @param rpcTimeoutMs          connect and read timeout of every RPC call
	 * @param oracleKeypair         Solana keypair file that signs the memos, created if missing; blank means
	 *                              a new in-memory key on every start
	 * @param oracleMinLamports     balance below which the oracle asks the faucet for more; 0 disables
	 * @param oracleAirdropLamports how much to ask the faucet for
	 */
	public record Blockchain(boolean enabled, String rpcUrl, long rpcTimeoutMs, String oracleKeypair,
			long oracleMinLamports, long oracleAirdropLamports) {
	}

}
