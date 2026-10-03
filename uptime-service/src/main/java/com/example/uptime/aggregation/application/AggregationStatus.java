package com.example.uptime.aggregation.application;

import java.time.Instant;

public record AggregationStatus(int openWindows, int pendingEvents, long rejectedChecks,
        long lateChecks, int consecutivePersistenceFailures, Instant nextRetryAt,
        String lastPersistenceFailure, long outOfOrderChecks, long capacityRejections,
        boolean activeSession) {
    public AggregationStatus(int openWindows, int pendingEvents, long rejectedChecks,
            long lateChecks, int consecutivePersistenceFailures, Instant nextRetryAt,
            String lastPersistenceFailure) {
        this(openWindows, pendingEvents, rejectedChecks, lateChecks, consecutivePersistenceFailures,
                nextRetryAt, lastPersistenceFailure, 0, 0, false);
    }
}
