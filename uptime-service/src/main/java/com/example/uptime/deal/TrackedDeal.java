package com.example.uptime.deal;

import java.time.Instant;

import com.example.uptime.deal.DealProgram.DealAccount;
import com.example.uptime.deal.DealProgram.Outcome;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A deal this service monitors as its oracle. Immutable; every change stores a new copy. The terms and
 * the counters mirror the on-chain {@code Deal} account, which is the authority; this copy is for display.
 *
 * @param address               Base58 address of the on-chain {@code Deal} account
 * @param payer                 Base58 customer wallet
 * @param recipient             Base58 provider wallet
 * @param amountLamports        the customer payment
 * @param providerStakeLamports the provider guarantee (0 for none)
 * @param durationSeconds       length of the window
 * @param checkIntervalSeconds  length of one monitoring round
 * @param minUptimeBps          required uptime in basis points
 * @param totalRounds           rounds in the window
 * @param startsAt              first second of the window (inclusive); {@code null} until the provider accepts
 * @param endsAt                end of the window (exclusive); {@code null} until the provider accepts
 * @param status                where the deal stands
 * @param upChecks              rounds recorded UP on chain, as last read
 * @param downChecks            rounds recorded DOWN on chain, as last read
 * @param observationsSent      {@code record_observation} transactions this service has sent
 * @param paidToRecipient       the program's verdict from its {@code DealSettled} event, once settled
 * @param signature             Base58 signature of the settlement transaction, once sent
 * @param sentAt                when the settlement was last sent
 * @param attempts              failed settlement attempts so far
 * @param error                 the last settlement error, if any
 */
@Schema(description = "An uptime deal this service monitors; counters and verdict mirror the chain")
public record TrackedDeal(String address, String payer, String recipient, long amountLamports,
		long providerStakeLamports, long durationSeconds, long checkIntervalSeconds, int minUptimeBps,
		int totalRounds, Instant startsAt, Instant endsAt, Status status, int upChecks, int downChecks,
		int observationsSent, Boolean paidToRecipient, String signature, Instant sentAt, int attempts,
		String error) {

	/** Lifecycle of a tracked deal. */
	public enum Status {

		/** The provider hasn't locked its guarantee; the window hasn't started. */
		AWAITING_PROVIDER,
		/** The window is running, or the settlement is being sent and confirmed. */
		ACTIVE,
		/** The program settled the deal and closed its account. */
		SETTLED,
		/**
		 * This service gave up settling, or couldn't explain a vanished account. Anyone can still call
		 * {@code settle_deal} if the account exists.
		 */
		FAILED,
		/** The payer withdrew the deal before the provider accepted it. */
		CANCELLED

	}

	/**
	 * Starts tracking a deal as it stands on chain.
	 *
	 * @param address Base58 deal address
	 * @param account the decoded account
	 * @return the tracked deal, {@code ACTIVE} or {@code AWAITING_PROVIDER}
	 */
	static TrackedDeal of(String address, DealAccount account) {
		return new TrackedDeal(address, account.payer(), account.recipient(), account.amountLamports(),
				account.providerStakeLamports(), account.durationSeconds(), account.checkIntervalSeconds(),
				account.minUptimeBps(), account.totalRounds(), account.startsAt(), account.endsAt(),
				account.active() ? Status.ACTIVE : Status.AWAITING_PROVIDER, account.upChecks(),
				account.downChecks(), 0, null, null, null, 0, null);
	}

	/** Refreshes the window and the counters from the account; a pending deal becomes active. */
	TrackedDeal withChain(DealAccount account) {
		Status next = status == Status.AWAITING_PROVIDER && account.active() ? Status.ACTIVE : status;
		return new TrackedDeal(address, payer, recipient, amountLamports, providerStakeLamports, durationSeconds,
				checkIntervalSeconds, minUptimeBps, totalRounds, account.startsAt(), account.endsAt(), next,
				account.upChecks(), account.downChecks(), observationsSent, paidToRecipient, signature, sentAt,
				attempts, error);
	}

	TrackedDeal observationsSent(int sent) {
		return copy(status, upChecks, downChecks, observationsSent + sent, paidToRecipient, signature, sentAt,
				attempts, error);
	}

	TrackedDeal sent(String sig, Instant at) {
		return copy(status, upChecks, downChecks, observationsSent, paidToRecipient, sig, at, attempts, null);
	}

	TrackedDeal settled(Outcome outcome) {
		if (outcome == null || outcome.cancelled()) {
			return copy(Status.SETTLED, upChecks, downChecks, observationsSent, null, signature, sentAt, attempts,
					null);
		}
		return copy(Status.SETTLED, outcome.upChecks(), outcome.downChecks(), observationsSent,
				outcome.paidToRecipient(), signature, sentAt, attempts, null);
	}

	TrackedDeal settledBy(String sig, Outcome outcome) {
		return copy(status, upChecks, downChecks, observationsSent, paidToRecipient, sig, sentAt, attempts, error)
			.settled(outcome);
	}

	TrackedDeal cancelled(String sig) {
		return copy(Status.CANCELLED, upChecks, downChecks, observationsSent, paidToRecipient, sig, sentAt,
				attempts, null);
	}

	TrackedDeal failedAttempt(String message, int maxAttempts) {
		int failed = attempts + 1;
		return copy(failed >= maxAttempts ? Status.FAILED : status, upChecks, downChecks, observationsSent,
				paidToRecipient, null, null, failed, message);
	}

	TrackedDeal failed(String message) {
		return copy(Status.FAILED, upChecks, downChecks, observationsSent, paidToRecipient, signature, sentAt,
				attempts, message);
	}

	private TrackedDeal copy(Status status, int up, int down, int observations, Boolean paid, String sig,
			Instant at, int tries, String message) {
		return new TrackedDeal(address, payer, recipient, amountLamports, providerStakeLamports, durationSeconds,
				checkIntervalSeconds, minUptimeBps, totalRounds, startsAt, endsAt, status, up, down, observations,
				paid, sig, at, tries, message);
	}

}
