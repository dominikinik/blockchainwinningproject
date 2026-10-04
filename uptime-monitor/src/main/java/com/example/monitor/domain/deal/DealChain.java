package com.example.monitor.domain.deal;

import java.time.Instant;

/**
 * Port: the {@code uptime_deal} program on the chain, as the oracle sees it. Every method may throw a
 * runtime exception when the node can't be reached.
 */
public interface DealChain {

	/**
	 * An on-chain {@code Deal} account: a proposal until the recipient accepts it, then an active deal.
	 *
	 * @param amountLamports       the payer's payment, locked at proposal
	 * @param guaranteeLamports    the recipient's guarantee, locked at acceptance
	 * @param durationSeconds      SLA window length
	 * @param checkIntervalSeconds length of one on-chain observation round
	 * @param minUptimeBps         required uptime threshold
	 * @param totalRounds          number of rounds in the SLA window
	 * @param upChecks             rounds recorded UP
	 * @param downChecks           rounds recorded DOWN
	 * @param recorded             per-round bitmap from the program
	 * @param acceptDeadline       chain time from which the proposal can no longer be accepted
	 * @param startsAt             chain time of the acceptance, where the window starts; {@code null} for a proposal
	 */
	record ChainDeal(String payer, String recipient, String oracle, long amountLamports, long guaranteeLamports,
			long durationSeconds, long checkIntervalSeconds, int minUptimeBps, int totalRounds, int upChecks,
			int downChecks, byte[] recorded, Instant acceptDeadline, Instant startsAt) {

		/** Whether the recipient accepted, so the window runs. */
		public boolean accepted() {
			return startsAt != null;
		}

		public boolean isRecorded(int round) {
			return round >= 0 && round / 8 < recorded.length && (recorded[round / 8] & (1 << (round % 8))) != 0;
		}

		/**
		 * The last round that ended at or before {@code at}: the round a heartbeat at {@code at} reports.
		 *
		 * @return the zero-based round, or {@code -1} for a proposal, before the first round ends, or after the last
		 */
		public int roundEndedBy(Instant at) {
			if (startsAt == null || checkIntervalSeconds <= 0) {
				return -1;
			}
			long round = Math.floorDiv(java.time.Duration.between(startsAt, at).toMillis(), checkIntervalSeconds * 1_000)
					- 1;
			return round < 0 || round >= totalRounds ? -1 : (int) round;
		}

		public boolean canSettleEarly() {
			long maximumPossibleUpRounds = totalRounds - downChecks;
			return maximumPossibleUpRounds * 10_000L < (long) minUptimeBps * totalRounds;
		}

	}

	/**
	 * How a deal account was closed: settled ({@code DealSettled}) or cancelled ({@code DealCancelled}).
	 * The settlement fields are {@code null} for a cancellation.
	 */
	record Closure(boolean cancelled, Boolean paidToRecipient, Long upChecks, Long totalRounds) {
	}

	/** A closing event found in a vanished deal's history, with the transaction that holds it. */
	record FoundClosure(String signature, Closure closure) {
	}

	/** @param error the transaction error, or {@code null} if it succeeded */
	record TxStatus(boolean confirmed, String error) {
	}

	String programId();

	String oracleAddress();

	String rpcUrl();

	/**
	 * Reads a deal account.
	 *
	 * @return the deal, or {@code null} when the account doesn't exist
	 * @throws IllegalArgumentException if the account isn't a deal of the program
	 */
	ChainDeal readDeal(String address);

	/** Records one UP/DOWN round; returns the transaction signature. */
	String recordObservation(String address, ChainDeal deal, int round, boolean up);

	/** Sends {@code settle_deal}; the program reads its own counters and decides the payout. */
	String sendSettle(String address, ChainDeal deal);

	/** @return the status, or {@code null} when the node hasn't seen the transaction */
	TxStatus status(String signature);

	/** @return how the transaction closed this deal, or {@code null} when its logs show neither event */
	Closure closureIn(String signature, String address);

	/**
	 * Searches the recent transactions of a vanished deal account for the event that closed it.
	 *
	 * @return the closure, or {@code null} when none of them names this deal
	 */
	FoundClosure findClosure(String address);

	/** Asks the faucet for lamports when the oracle can't pay fees (localnet/devnet); never throws. */
	void ensureOracleFunded();

}
