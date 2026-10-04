package com.example.monitor.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Port: the {@code uptime_deal} program on the chain, as the proxy sees it. It only lists the deals that name this
 * proxy's key as their oracle and records UP/DOWN rounds on them; the program keeps the counters and decides the
 * payout. Every method except {@link #ensureOracleFunded} may throw a runtime exception when the node can't be
 * reached or rejects a transaction.
 */
public interface DealChain {

	/**
	 * An accepted deal whose window runs, as read from its account.
	 *
	 * @param address              Base58 address of the {@code Deal} account
	 * @param startsAt             chain time at which the window started
	 * @param checkIntervalSeconds length of one round
	 * @param totalRounds          rounds in the window
	 * @param recorded             the program's per-round bitmap (bit {@code r % 8} of byte {@code r / 8})
	 */
	record ActiveDeal(String address, Instant startsAt, long checkIntervalSeconds, int totalRounds, byte[] recorded) {

		public boolean isRecorded(int round) {
			return round >= 0 && round / 8 < recorded.length && (recorded[round / 8] & (1 << (round % 8))) != 0;
		}

		/**
		 * The last round that ended at or before {@code at}: the one a health result observed at {@code at} reports.
		 *
		 * @return the zero-based round, or {@code -1} when none has ended yet or the window is over
		 */
		public int roundEndedBy(Instant at) {
			long elapsedMillis = Duration.between(startsAt, at).toMillis();
			long round = Math.floorDiv(elapsedMillis, checkIntervalSeconds * 1_000) - 1;
			return round < 0 || round >= totalRounds ? -1 : (int) round;
		}

	}

	String programId();

	String oracleAddress();

	String rpcUrl();

	/** Every accepted deal naming this oracle; proposals that still await the provider are left out. */
	List<ActiveDeal> activeDeals();

	/**
	 * Sends {@code record_observation} for one round, signed by the oracle.
	 *
	 * @return the transaction signature
	 */
	String recordObservation(String address, int round, boolean up);

	/** Asks the faucet for lamports when the oracle can't pay fees (localnet/devnet); never throws. */
	void ensureOracleFunded();

}
