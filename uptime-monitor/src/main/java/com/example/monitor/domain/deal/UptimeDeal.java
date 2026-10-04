package com.example.monitor.domain.deal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.example.monitor.domain.ServiceId;

/**
 * Aggregate root: one on-chain {@code uptime_deal} that this monitor settles as its oracle, measured
 * against the deal's own tracked service ({@link #serviceIdFor}), which watches {@code healthUrl} from the
 * acceptance until the deal is finished. Immutable; every transition returns a new copy that the caller stores.
 * <p>
 * Life cycle: {@code PROPOSED} while the recipient hasn't accepted (no window runs), {@code ACTIVE} from the
 * acceptance ({@link #accepted}) until a settlement is <em>decided</em> ({@link #decide}, which fixes
 * {@code upSeconds}/{@code totalSeconds}), then sent ({@link #sent}) and confirmed ({@link #settled}), or
 * found closed on chain ({@link #settledBy}, {@link #cancelled}), or given up ({@link #failed}).
 *
 * @param address           Base58 address of the on-chain {@code Deal} account
 * @param serviceId         the deal's own tracked service, whose events measure it ({@link #serviceIdFor})
 * @param healthUrl         the health endpoint that service checks
 * @param payer             proposed the deal and paid {@code amountLamports}
 * @param recipient         the provider: accepts, pays {@code guaranteeLamports}, and gets both deposits above 99%
 * @param guaranteeLamports the recipient's guarantee, locked at acceptance
 * @param durationSeconds   length of the window, from the deal account; also the {@code total_seconds} sent
 * @param acceptDeadline    when the proposal stops being acceptable on chain
 * @param startsAt          first second of the window (inclusive): the chain time of the acceptance;
 *                          {@code null} while {@code PROPOSED}
 * @param upSeconds       the decided up seconds, once a settlement is decided
 * @param totalSeconds    the decided total seconds, once a settlement is decided
 * @param paidToRecipient the program's verdict from its {@code DealSettled} event, once settled
 * @param signature       Base58 signature of the pending or final settlement transaction
 * @param sentAt          when the settlement was last sent
 * @param attempts        failed settlement attempts so far
 * @param error           the last settlement error, if any
 */
public record UptimeDeal(String address, ServiceId serviceId, String healthUrl, String payer, String recipient, long amountLamports,
		long guaranteeLamports, long durationSeconds, Instant acceptDeadline, Instant startsAt, Status status, Long upSeconds, Long totalSeconds,
		Boolean paidToRecipient, String signature, Instant sentAt, int attempts, String error, Instant registeredAt) {

	public enum Status {

		/** The payer proposed the deal; the recipient hasn't accepted it, so no window runs yet. */
		PROPOSED,
		/** The window is running, or a decided settlement is being sent and confirmed. */
		ACTIVE,
		/** The program settled the deal and closed its account. */
		SETTLED,
		/**
		 * Settling failed for good, or the outcome couldn't be determined. Both deposits stay in the deal account
		 * until the payer or the recipient calls {@code cancel_deal} after the program's timeout.
		 */
		FAILED,
		/** A party cancelled the deal on chain; each deposit went back to the party that paid it. */
		CANCELLED

	}

	public UptimeDeal {
		Objects.requireNonNull(address, "address");
		Objects.requireNonNull(serviceId, "serviceId");
		Objects.requireNonNull(healthUrl, "healthUrl");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(acceptDeadline, "acceptDeadline");
		if (startsAt == null && status == Status.ACTIVE) {
			throw new IllegalArgumentException("An active deal needs its window start");
		}
	}

	/**
	 * The service a deal is tracked under: a UUID derived from its address, so every deal has its own stream of
	 * events and its monitoring can be started and stopped without touching any other service.
	 */
	public static ServiceId serviceIdFor(String address) {
		return new ServiceId(UUID.nameUUIDFromBytes(("uptime-deal:" + address).getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * A newly registered deal: {@code PROPOSED} when {@code startsAt} is {@code null} (not accepted yet),
	 * otherwise {@code ACTIVE} with its window.
	 */
	public static UptimeDeal register(String address, String healthUrl, String payer, String recipient,
			long amountLamports, long guaranteeLamports, long durationSeconds, Instant acceptDeadline, Instant startsAt,
			Instant now) {
		return new UptimeDeal(address, serviceIdFor(address), healthUrl, payer, recipient, amountLamports,
				guaranteeLamports, durationSeconds, acceptDeadline, startsAt, startsAt == null ? Status.PROPOSED : Status.ACTIVE, null, null, null, null, null,
				0, null, now);
	}

	/** End of the window (exclusive); {@code null} while {@code PROPOSED}. */
	public Instant endsAt() {
		return startsAt == null ? null : startsAt.plusSeconds(durationSeconds);
	}

	/**
	 * The recipient accepted on chain: the window starts at the acceptance.
	 *
	 * @throws IllegalStateException if the deal isn't a proposal
	 */
	public UptimeDeal accepted(Instant windowStart) {
		if (status != Status.PROPOSED) {
			throw new IllegalStateException("Deal " + address + " is not a proposal");
		}
		return new UptimeDeal(address, serviceId, healthUrl, payer, recipient, amountLamports, guaranteeLamports,
				durationSeconds, acceptDeadline, Objects.requireNonNull(windowStart, "windowStart"), Status.ACTIVE, upSeconds,
				totalSeconds, paidToRecipient, signature, sentAt, attempts, error, registeredAt);
	}

	/** Active and no settlement decided yet: events and the window end may still decide one. */
	public boolean isOpen() {
		return status == Status.ACTIVE && upSeconds == null;
	}

	/** Settled, cancelled or failed: nothing about the deal changes any more, so its service needs no tracking. */
	public boolean isFinished() {
		return status == Status.SETTLED || status == Status.CANCELLED || status == Status.FAILED;
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
		return new UptimeDeal(address, serviceId, healthUrl, payer, recipient, amountLamports, guaranteeLamports,
				durationSeconds, acceptDeadline, startsAt, newStatus, up, total, paid, sig, at, tries, err, registeredAt);
	}

}
