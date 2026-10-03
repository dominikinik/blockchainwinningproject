package com.example.uptime.aggregation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record UptimeEvent(UUID id, UUID sessionId, Instant bucketStart, Instant windowStart,
        Instant windowEnd, EventStatus status, int totalChecks, int successfulChecks,
        boolean partialCoverage, List<BadEvent> badEvents, List<TimeRange> unknownIntervals) {
    public UptimeEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(bucketStart, "bucketStart");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(status, "status");
        badEvents = List.copyOf(badEvents);
        unknownIntervals = List.copyOf(unknownIntervals);
        if (!bucketStart.equals(UptimeWindowPolicy.bucketStart(bucketStart)) || windowStart.isBefore(bucketStart)
                || windowEnd.isAfter(UptimeWindowPolicy.end(bucketStart)) || windowEnd.isBefore(windowStart)
                || totalChecks < 0 || successfulChecks < 0 || successfulChecks > totalChecks) {
            throw new IllegalArgumentException("Invalid window bounds or counts");
        }
        EventStatus expected = !badEvents.isEmpty() ? EventStatus.FAILED
                : !unknownIntervals.isEmpty() ? EventStatus.UNKNOWN : EventStatus.SUCCESS;
        if (status != expected) throw new IllegalArgumentException("Status does not match coverage");
        for (BadEvent bad : badEvents) {
            if (!bad.uptimeEventId().equals(id) || !bad.sessionId().equals(sessionId)
                    || bad.windowStart().isBefore(windowStart) || bad.windowEnd().isAfter(windowEnd)) {
                throw new IllegalArgumentException("Bad event outside parent");
            }
        }
        for (TimeRange range : unknownIntervals) {
            if (range.start().isBefore(windowStart) || range.end().isAfter(windowEnd)) {
                throw new IllegalArgumentException("Unknown interval outside parent");
            }
        }
    }

    public int failedChecks() { return totalChecks - successfulChecks; }
    public List<UUID> badEventIds() { return badEvents.stream().map(BadEvent::id).toList(); }
}
