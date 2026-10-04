package com.example.monitor.domain.deal;

import java.time.Instant;

/**
 * Port: the {@code uptime_deal} program on the chain, as the oracle sees it. Every method may throw a
 * runtime exception when the node can't be reached.
 */
public interface DealChain {

	/**
	 * An on-chain {@code Deal} account.
	 *
	 * @param startsAt chain time at creation; the window starts here
	 */
	record ChainDeal(String payer, String recipient, String oracle, long amountLamports, Instant startsAt,
			long durationSeconds) {
	}

	/**
	 * How a deal account was closed: settled ({@code DealSettled}) or cancelled ({@code DealCancelled}).
	 * The settlement fields are {@code null} for a cancellation.
	 */
	record Closure(boolean cancelled, Boolean paidToRecipient, Long upSeconds, Long totalSeconds) {
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

	/** Sends {@code settle_deal}; returns the transaction signature. */
	String sendSettle(String address, ChainDeal deal, Verdict verdict);

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
