package com.example.uptime.aggregation.application;

import java.time.Instant;

public class OutsideTrackingCoverageException extends RuntimeException {
	private final Instant from;
	private final Instant to;

	public OutsideTrackingCoverageException(Instant from, Instant to) {
		super("Requested interval is outside tracking coverage");
		this.from = from;
		this.to = to;
	}

	public Instant from() { return from; }
	public Instant to() { return to; }
}
