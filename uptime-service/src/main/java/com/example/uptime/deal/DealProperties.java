package com.example.uptime.deal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param rpcUrl                Solana JSON-RPC URL of the cluster the program runs on
 * @param programId             address of the {@code uptime_deal} program
 * @param oracleKeypair         Solana keypair file of the oracle key, created if missing; blank means
 *                              a new in-memory key on every start
 * @param settleGraceSeconds    how long after a window ends to wait for its last second to be flushed
 * @param pollIntervalMs        how often due deals are settled and sent settlements checked
 * @param maxDurationSeconds    longest on-chain window a deal may have to be registered
 * @param maxSettleAttempts     failed sends after which a deal is marked {@code FAILED}
 * @param confirmTimeoutSeconds how long to wait for a sent settlement before sending it again
 * @param oracleMinLamports     oracle balance below which it asks the faucet for more; 0 disables
 * @param oracleAirdropLamports how much to ask the faucet for
 * @param rpcTimeoutMs          connect and read timeout of every RPC call
 */
@ConfigurationProperties("deal")
public record DealProperties(String rpcUrl, String programId, String oracleKeypair, long settleGraceSeconds,
		long pollIntervalMs, long maxDurationSeconds, int maxSettleAttempts, long confirmTimeoutSeconds,
		long oracleMinLamports, long oracleAirdropLamports, long rpcTimeoutMs) {
}
