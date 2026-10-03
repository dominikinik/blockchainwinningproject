package com.example.uptime.checking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CheckResult(UUID checkId, Instant startedAt, Instant observedAt, long durationNanos,
		CheckOutcome outcome, CheckFailure failure) {
	public CheckResult {
		Objects.requireNonNull(checkId, "checkId");
		Objects.requireNonNull(startedAt, "startedAt");
		Objects.requireNonNull(observedAt, "observedAt");
		Objects.requireNonNull(outcome, "outcome");
		if (durationNanos < 0) {
			throw new IllegalArgumentException("durationNanos must be nonnegative");
		}
		if (observedAt.isBefore(startedAt)) {
			throw new IllegalArgumentException("observedAt must not precede startedAt");
		}
		if ((outcome == CheckOutcome.FAILURE) != (failure != null)) {
			throw new IllegalArgumentException("failure is required exactly for FAILURE");
		}
	}
}
