package com.example.monitor.domain;

import java.util.Objects;
import java.util.Set;

/**
 * What one call to a service's health endpoint returned, classified by the tracking rules:
 * <ul>
 * <li>200 with a reported status of {@code DOWN} or {@code OUT_OF_SERVICE}, or 404 → {@link Outcome#DOWN}</li>
 * <li>200 with any other (or no) reported status → {@link Outcome#HEALTHY}</li>
 * <li>any other status, or no response at all → {@link Outcome#INTERNAL_ERROR}</li>
 * </ul>
 *
 * @param httpStatus the status code, or {@code null} when the service didn't answer
 * @param detail     a human-readable description of the response
 */
public record HealthCheckResult(Outcome outcome, Integer httpStatus, String detail) {

	public enum Outcome {
		HEALTHY, DOWN, INTERNAL_ERROR
	}

	private static final Set<String> DOWN_STATUSES = Set.of("DOWN", "OUT_OF_SERVICE");

	public HealthCheckResult {
		Objects.requireNonNull(outcome, "outcome");
	}

	/**
	 * Classifies an HTTP response.
	 *
	 * @param httpStatus     the status code
	 * @param reportedStatus the {@code status} field of the body, or {@code null} if there was none
	 */
	public static HealthCheckResult fromResponse(int httpStatus, String reportedStatus) {
		if (httpStatus == 200) {
			boolean down = reportedStatus != null && DOWN_STATUSES.contains(reportedStatus.trim().toUpperCase());
			return new HealthCheckResult(down ? Outcome.DOWN : Outcome.HEALTHY, httpStatus,
					"HTTP 200, status " + reportedStatus);
		}
		if (httpStatus == 404) {
			return new HealthCheckResult(Outcome.DOWN, httpStatus, "HTTP 404");
		}
		return new HealthCheckResult(Outcome.INTERNAL_ERROR, httpStatus, "HTTP " + httpStatus);
	}

	/**
	 * A call that got no HTTP response (connection refused, timeout, bad host, ...).
	 *
	 * @param detail what went wrong
	 */
	public static HealthCheckResult unreachable(String detail) {
		return new HealthCheckResult(Outcome.INTERNAL_ERROR, null, detail);
	}

}
