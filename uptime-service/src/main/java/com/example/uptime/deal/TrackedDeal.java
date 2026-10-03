package com.example.uptime.deal;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A deal this service watches and will settle. Immutable; every change stores a new copy.
 *
 * @param address           Base58 address of the on-chain {@code Deal} account
 * @param payer             Base58 wallet that proposed the deal and paid {@code amountLamports}
 * @param recipient         Base58 wallet that must accept, pays {@code guaranteeLamports}, and receives
 *                          both deposits when uptime is above 99%
 * @param amountLamports    the payer's payment
 * @param guaranteeLamports the recipient's guarantee, locked when it accepts
 * @param durationSeconds   length of the measured window, from the deal account
 * @param acceptDeadline    when the proposal stops being acceptable on chain
 * @param startsAt          first second of the window (inclusive); {@code null} until the recipient accepts
 * @param endsAt            end of the window (exclusive); {@code null} until the recipient accepts
 * @param status            where the deal stands
 * @param upSeconds         up seconds reported to the program, once measured
 * @param totalSeconds      seconds measured, once measured
 * @param paidToRecipient   the program's verdict from its {@code DealSettled} event, once settled
 * @param signature         Base58 signature of the settlement transaction, once sent
 * @param sentAt            when the settlement was last sent
 * @param attempts          failed settlement attempts so far
 * @param error             the last settlement error, if any
 */
@Schema(description = "An uptime deal watched and settled by this service")
public record TrackedDeal(String address, String payer, String recipient, long amountLamports,
		long guaranteeLamports, long durationSeconds, Instant acceptDeadline, Instant startsAt, Instant endsAt,
		Status status, Long upSeconds, Long totalSeconds, Boolean paidToRecipient, String signature, Instant sentAt,
		int attempts, String error) {

	/** Lifecycle of a tracked deal. */
	public enum Status {

		/**
		 * The payer proposed the deal; the recipient hasn't accepted it yet, so no window runs. The service
		 * re-reads the account until it is accepted or closed.
		 */
		PROPOSED,
		/** The window is running, or the settlement is being sent and confirmed. */
		ACTIVE,
		/** The program settled the deal and closed its account. */
		SETTLED,
		/**
		 * Settling failed for good, or the outcome couldn't be determined. Both deposits stay in the deal
		 * account until the payer or the recipient calls {@code cancel_deal} after the program's timeout.
		 */
		FAILED,
		/** A party cancelled the deal on chain; each deposit went back to the party that paid it. */
		CANCELLED

	}

	TrackedDeal accepted(Instant start, Instant end) {
		return new TrackedDeal(address, payer, recipient, amountLamports, guaranteeLamports, durationSeconds,
				acceptDeadline, start, end, Status.ACTIVE, upSeconds, totalSeconds, paidToRecipient, signature, sentAt,
				attempts, error);
	}

	TrackedDeal sent(long up, long total, String sig, Instant at) {
		return new TrackedDeal(address, payer, recipient, amountLamports, guaranteeLamports, durationSeconds,
				acceptDeadline, startsAt, endsAt, status, up, total, paidToRecipient, sig, at, attempts, null);
	}

	TrackedDeal settled(Boolean paid) {
		return new TrackedDeal(address, payer, recipient, amountLamports, guaranteeLamports, durationSeconds,
				acceptDeadline, startsAt, endsAt, Status.SETTLED, upSeconds, totalSeconds, paid, signature, sentAt,
				attempts, null);
	}

	TrackedDeal settledBy(String sig, Boolean paid, Long up, Long total) {
		return new TrackedDeal(address, payer, recipient, amountLamports, guaranteeLamports, durationSeconds,
				acceptDeadline, startsAt, endsAt, Status.SETTLED, up, total, paid, sig, sentAt, attempts, null);
	}

	TrackedDeal cancelled(String sig) {
		return new TrackedDeal(address, payer, recipient, amountLamports, guaranteeLamports, durationSeconds,
				acceptDeadline, startsAt, endsAt, Status.CANCELLED, upSeconds, totalSeconds, paidToRecipient, sig,
				sentAt, attempts, null);
	}

	TrackedDeal failedAttempt(String message, int maxAttempts) {
		int failed = attempts + 1;
		return new TrackedDeal(address, payer, recipient, amountLamports, guaranteeLamports, durationSeconds,
				acceptDeadline, startsAt, endsAt, failed >= maxAttempts ? Status.FAILED : status, upSeconds,
				totalSeconds, paidToRecipient, null, null, failed, message);
	}

	TrackedDeal failed(String message) {
		return new TrackedDeal(address, payer, recipient, amountLamports, guaranteeLamports, durationSeconds,
				acceptDeadline, startsAt, endsAt, Status.FAILED, upSeconds, totalSeconds, paidToRecipient, signature,
				sentAt, attempts, message);
	}

}
