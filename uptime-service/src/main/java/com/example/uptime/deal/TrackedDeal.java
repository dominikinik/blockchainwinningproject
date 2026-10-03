package com.example.uptime.deal;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A deal this service watches and will settle. Immutable; every change stores a new copy.
 *
 * @param address         Base58 address of the on-chain {@code Deal} account
 * @param payer           Base58 wallet that funded the escrow
 * @param recipient       Base58 wallet paid when uptime is above 99%
 * @param amountLamports  the escrowed lamports
 * @param durationSeconds length of the measured window
 * @param startsAt        first second of the window (inclusive)
 * @param endsAt          end of the window (exclusive)
 * @param status          where the deal stands
 * @param upSeconds       up seconds reported to the program, once measured
 * @param totalSeconds    seconds measured, once measured
 * @param paidToRecipient the program's verdict from its {@code DealSettled} event, once settled
 * @param signature       Base58 signature of the settlement transaction, once sent
 * @param sentAt          when the settlement was last sent
 * @param attempts        failed settlement attempts so far
 * @param error           the last settlement error, if any
 */
@Schema(description = "An uptime deal watched and settled by this service")
public record TrackedDeal(String address, String payer, String recipient, long amountLamports, long durationSeconds,
		Instant startsAt, Instant endsAt, Status status, Long upSeconds, Long totalSeconds, Boolean paidToRecipient,
		String signature, Instant sentAt, int attempts, String error) {

	/** Lifecycle of a tracked deal. */
	public enum Status {

		/** The window is running, or the settlement is being sent and confirmed. */
		ACTIVE,
		/** The program settled the deal and closed its account. */
		SETTLED,
		/** Settling failed for good; the escrow stays locked in the deal account. */
		FAILED

	}

	TrackedDeal sent(long up, long total, String sig, Instant at) {
		return new TrackedDeal(address, payer, recipient, amountLamports, durationSeconds, startsAt, endsAt, status, up,
				total, paidToRecipient, sig, at, attempts, null);
	}

	TrackedDeal settled(Boolean paid) {
		return new TrackedDeal(address, payer, recipient, amountLamports, durationSeconds, startsAt, endsAt,
				Status.SETTLED, upSeconds, totalSeconds, paid, signature, sentAt, attempts, null);
	}

	TrackedDeal failedAttempt(String message, int maxAttempts) {
		int failed = attempts + 1;
		return new TrackedDeal(address, payer, recipient, amountLamports, durationSeconds, startsAt, endsAt,
				failed >= maxAttempts ? Status.FAILED : status, upSeconds, totalSeconds, paidToRecipient, null, null,
				failed, message);
	}

	TrackedDeal failed(String message) {
		return new TrackedDeal(address, payer, recipient, amountLamports, durationSeconds, startsAt, endsAt,
				Status.FAILED, upSeconds, totalSeconds, paidToRecipient, signature, sentAt, attempts, message);
	}

}
