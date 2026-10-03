package com.example.uptime.aggregation.application;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonFormat;

/** A session-specific second; unknown and pending observations are not downtime. */
public record UptimePoint(
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant time, Boolean down, UUID sessionId,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant windowStart,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant windowEnd,
		String status, boolean partialCoverage) {
}
