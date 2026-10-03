package com.example.uptime.aggregation.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.example.uptime.aggregation.domain.EventStatus;
import com.example.uptime.aggregation.domain.TimeRange;

public record UptimeHistoryEntry(UUID eventId, UUID sessionId, Instant bucketStart,
		Instant windowStart, Instant windowEnd, EventStatus status, int totalChecks,
		int successfulChecks, boolean partialCoverage, List<UUID> badEventIds,
		List<TimeRange> unknownIntervals) {
	public UptimeHistoryEntry {
		badEventIds = List.copyOf(badEventIds);
		unknownIntervals = List.copyOf(unknownIntervals);
	}
}
