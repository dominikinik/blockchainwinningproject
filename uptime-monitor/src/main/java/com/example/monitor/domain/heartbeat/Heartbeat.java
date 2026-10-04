package com.example.monitor.domain.heartbeat;

import java.time.Instant;
import java.util.Objects;

import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthCheckResult.Outcome;

/**
 * One heartbeat of a deal: the provider probe the oracle made when one of the deal's rounds ended, and what became of
 * the {@code record_observation} that reported it. Immutable; the report transitions return a copy.
 *
 * @param id          the log's id, or {@code null} before it is appended
 * @param round       the deal's on-chain round index, from 0
 * @param checkedAt   when the probe started
 * @param httpStatus  the provider's status code, or {@code null} when it didn't answer
 * @param detail      a human-readable description of the response
 * @param latencyMs   how long the probe took
 * @param reportError why the last send failed, or {@code null}
 * @param signature   the {@code record_observation} transaction, once sent
 */
public record Heartbeat(Long id, String dealAddress, int round, Instant checkedAt, Outcome outcome, Integer httpStatus,
		String detail, long latencyMs, Report report, String reportError, String signature) {

	/** Where the round's observation is on its way to the chain. */
	public enum Report {
		/** The observation transaction was sent. */
		SENT,
		/** The first send failed; it is retried every tick. */
		RETRYING,
		/** The deal stopped accepting observations, or the round was recorded otherwise, before a retry landed. */
		DROPPED
	}

	public Heartbeat {
		Objects.requireNonNull(dealAddress, "dealAddress");
		Objects.requireNonNull(checkedAt, "checkedAt");
		Objects.requireNonNull(outcome, "outcome");
		Objects.requireNonNull(report, "report");
		if (round < 0) {
			throw new IllegalArgumentException("round must not be negative");
		}
		if (latencyMs < 0) {
			throw new IllegalArgumentException("latencyMs must not be negative");
		}
	}

	/** A heartbeat whose observation hasn't been sent yet ({@link Report#RETRYING} until {@link #sent}). */
	public static Heartbeat probed(String dealAddress, int round, Instant checkedAt, HealthCheckResult result,
			long latencyMs) {
		return new Heartbeat(null, dealAddress, round, checkedAt, result.outcome(), result.httpStatus(), result.detail(),
				latencyMs, Report.RETRYING, null, null);
	}

	/** Only a healthy provider counts as UP. */
	public boolean up() {
		return outcome == Outcome.HEALTHY;
	}

	public Heartbeat withId(long id) {
		return new Heartbeat(id, dealAddress, round, checkedAt, outcome, httpStatus, detail, latencyMs, report,
				reportError, signature);
	}

	public Heartbeat sent(String signature) {
		return new Heartbeat(id, dealAddress, round, checkedAt, outcome, httpStatus, detail, latencyMs, Report.SENT,
				null, signature);
	}

	public Heartbeat retrying(String error) {
		return new Heartbeat(id, dealAddress, round, checkedAt, outcome, httpStatus, detail, latencyMs, Report.RETRYING,
				error, signature);
	}

	public Heartbeat dropped(String reason) {
		return new Heartbeat(id, dealAddress, round, checkedAt, outcome, httpStatus, detail, latencyMs, Report.DROPPED,
				reason, signature);
	}

}
