package com.example.uptime.deal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param rpcUrl                Solana JSON-RPC URL of the cluster the program runs on
 * @param programId             address of the {@code uptime_deal} program
 * @param oracleKeypair         Solana keypair file of the oracle key, created if missing; blank means
 *                              a new in-memory key on every start
 * @param settleGraceSeconds    extra wait after the program opens settlement, for clock skew between this
 *                              host and the chain
 * @param pollIntervalMs        how often deals are refreshed from chain, finished rounds reported, and due
 *                              deals settled
 * @param maxDurationSeconds    longest on-chain window a deal may have to be monitored
 * @param maxSettleAttempts     failed settlement sends after which a deal is marked {@code FAILED}
 * @param confirmTimeoutSeconds how long to wait for a sent settlement before sending it again
 * @param oracleMinLamports     oracle balance below which it asks the faucet for more; 0 disables
 * @param oracleAirdropLamports how much to ask the faucet for
 * @param rpcTimeoutMs          connect and read timeout of every RPC call
 * @param observeIntervalMs     how often the service samples its health for the deals' current rounds;
 *                              must be well below the shortest check interval
 * @param observationRetryMs    how long after sending a round's observation to send it again if the chain
 *                              still hasn't recorded it
 * @param discoverIntervalMs    how often to look for open deals naming this oracle (also runs at startup)
 */
@ConfigurationProperties("deal")
public record DealProperties(String rpcUrl, String programId, String oracleKeypair, long settleGraceSeconds,
		long pollIntervalMs, long maxDurationSeconds, int maxSettleAttempts, long confirmTimeoutSeconds,
		long oracleMinLamports, long oracleAirdropLamports, long rpcTimeoutMs, long observeIntervalMs,
		long observationRetryMs, long discoverIntervalMs) {
}
