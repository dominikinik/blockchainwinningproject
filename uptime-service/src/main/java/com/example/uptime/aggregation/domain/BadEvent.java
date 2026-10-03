package com.example.uptime.aggregation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BadEvent(UUID id, UUID uptimeEventId, UUID sessionId, BadEventType type,
        Instant windowStart, Instant windowEnd, Instant firstObservedAt, Instant lastObservedAt,
        int observationCount, FailureKey failureKey, String diagnosticSummary) {
    public BadEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(uptimeEventId, "uptimeEventId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(firstObservedAt, "firstObservedAt");
        Objects.requireNonNull(lastObservedAt, "lastObservedAt");
        Objects.requireNonNull(failureKey, "failureKey");
        if (windowEnd.isBefore(windowStart) || lastObservedAt.isBefore(firstObservedAt)
                || observationCount < 0 || type != failureKey.type()) {
            throw new IllegalArgumentException("Invalid bad event");
        }
    }
}
