package com.example.monitor.domain.deal;

import java.time.Instant;
import java.util.Objects;

import com.example.monitor.domain.ServiceId;

/**
 * Aggregate root: one on-chain {@code uptime_deal} that this monitor settles as its oracle, measured
 * against one tracked service. Immutable; every transition returns a new copy that the caller stores.
 * <p>
 * Life cycle: {@code ACTIVE} until a settlement is <em>decided</em> ({@link #decide}, which fixes
 * {@code upSeconds}/{@code totalSeconds}), then sent ({@link #sent}) and confirmed ({@link #settled}), or
 * found closed on chain ({@link #settledBy}, {@link #cancelled}), or given up ({@link #failed}).
 *
 * @param address         Base58 address of the on-chain {@code Deal} account
 * @param serviceId       the tracked service whose events measure the deal
 * @param durationSeconds length of the window, from the deal account; also the {@code total_seconds} sent
 * @param startsAt        first second of the window (inclusive), from the deal account
 * @param upSeconds       the decided up seconds, once a settlement is decided
 * @param totalSeconds    the decided total seconds, once a settlement is decided
 * @param paidToRecipient the program's verdict from its {@code DealSettled} event, once settled
 * @param signature       Base58 signature of the pending or final settlement transaction
 * @param sentAt          when the settlement was last sent
 * @param attempts        failed settlement attempts so far
 * @param error           the last settlement error, if any
 */
public record UptimeDeal(String address, ServiceId serviceId, String payer, String recipient, long amountLamports,
		long durationSeconds, Instant startsAt, Status status, Long upSeconds, Long totalSeconds,
		Boolean paidToRecipient, String signature, Instant sentAt, int attempts, String error, Instant registeredAt) {

	public enum Status {

		/** The window is running, or a decided settlement is being sent and confirmed. */
		ACTIVE,
		/** The program settled the deal and closed its account. */
		SETTLED,
		/**
		 * Settling failed for good, or the outcome couldn't be determined. The escrow stays in the deal account
		 * until the payer calls {@code cancel_deal} after the program's timeout.
		 */
		FAILED,
		/** The payer cancelled the deal on chain; the escrow went back to the payer. */
		CANCELLED

	}

	public UptimeDeal {
		Objects.requireNonNull(address, "address");
		Objects.requireNonNull(serviceId, "serviceId");
		Objects.requireNonNull(startsAt, "startsAt");
		Objects.requireNonNull(status, "status");
	}

	/** A newly registered deal. */
	public static UptimeDeal register(String address, ServiceId serviceId, String payer, String recipient,
			long amountLamports, long durationSeconds, Instant startsAt, Instant now) {
		return new UptimeDeal(address, serviceId, payer, recipient, amountLamports, durationSeconds, startsAt,
				Status.ACTIVE, null, null, null, null, null, 0, null, now);
	}

	/** End of the window (exclusive). */
	public Instant endsAt() {
		return startsAt.plusSeconds(durationSeconds);
	}

	/** Active and no settlement decided yet: events and the window end may still decide one. */
	public boolean isOpen() {
		return status == Status.ACTIVE && upSeconds == null;
	}

	/** Active with a decided settlement that still has to be sent or confirmed. */
	public boolean isSettling() {
		return status == Status.ACTIVE && upSeconds != null;
	}

	/**
	 * Fixes the settlement to send. Only an open deal can be decided; the verdict never changes afterwards.
	 *
	 * @throws IllegalStateException if a settlement was already decided or the deal is closed
	 */
	public UptimeDeal decide(Verdict verdict) {
		if (!isOpen()) {
			throw new IllegalStateException("Deal " + address + " is not open");
		}
		return with(Status.ACTIVE, verdict.upSeconds(), verdict.totalSeconds(), null, null, null, attempts, null);
	}

	public UptimeDeal sent(String sig, Instant at) {
		return with(status, upSeconds, totalSeconds, paidToRecipient, sig, at, attempts, null);
	}

	public UptimeDeal settled(Boolean paid) {
		return with(Status.SETTLED, upSeconds, totalSeconds, paid, signature, sentAt, attempts, null);
	}

	public UptimeDeal settledBy(String sig, Boolean paid, Long up, Long total) {
		return with(Status.SETTLED, up, total, paid, sig, sentAt, attempts, null);
	}

	public UptimeDeal cancelled(String sig) {
		return with(Status.CANCELLED, upSeconds, totalSeconds, paidToRecipient, sig, sentAt, attempts, null);
	}

	/** One more failed attempt; the deal fails for good after {@code maxAttempts}. Clears the signature. */
	public UptimeDeal failedAttempt(String message, int maxAttempts) {
		int failed = attempts + 1;
		return with(failed >= maxAttempts ? Status.FAILED : status, upSeconds, totalSeconds, paidToRecipient, null,
				null, failed, message);
	}

	public UptimeDeal failed(String message) {
		return with(Status.FAILED, upSeconds, totalSeconds, paidToRecipient, signature, sentAt, attempts, message);
	}

	private UptimeDeal with(Status newStatus, Long up, Long total, Boolean paid, String sig, Instant at, int tries,
			String err) {
		return new UptimeDeal(address, serviceId, payer, recipient, amountLamports, durationSeconds, startsAt, newStatus,
				up, total, paid, sig, at, tries, err, registeredAt);
	}

}
