package com.example.uptime.checking.domain;

import java.util.Objects;

public record ProbeResult(CheckOutcome outcome, CheckFailure failure) {
	public ProbeResult {
		Objects.requireNonNull(outcome, "outcome");
		if ((outcome == CheckOutcome.FAILURE) != (failure != null)) {
			throw new IllegalArgumentException("failure is required exactly for FAILURE");
		}
	}

	public static ProbeResult success() {
		return new ProbeResult(CheckOutcome.SUCCESS, null);
	}

	public static ProbeResult failure(String code, String message, FailureType type, String reason) {
		return new ProbeResult(CheckOutcome.FAILURE, new CheckFailure(code, message, type, reason));
	}

	public static ProbeResult failure(String code, String message) {
		return new ProbeResult(CheckOutcome.FAILURE, new CheckFailure(code, message));
	}
}
