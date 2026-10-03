package com.example.uptime.aggregation.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.example.uptime.aggregation.domain.BadEvent;
import com.example.uptime.aggregation.domain.TimeRange;

public record UptimeEventDetails(UUID eventId, UUID sessionId,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant bucketStart,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant windowStart,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant windowEnd,
		String status, int totalChecks, int successfulChecks, int failedChecks,
		boolean partialCoverage, List<UUID> badEventIds, List<BadEvent> badEvents,
		List<TimeRange> unknownIntervals) {
	public UptimeEventDetails {
		badEventIds = List.copyOf(badEventIds);
		badEvents = List.copyOf(badEvents);
		unknownIntervals = List.copyOf(unknownIntervals);
	}
}
