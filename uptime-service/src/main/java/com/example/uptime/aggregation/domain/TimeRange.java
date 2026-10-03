package com.example.uptime.aggregation.domain;

import java.time.Instant;
import java.util.Objects;

public record TimeRange(Instant start, Instant end) {
    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) throw new IllegalArgumentException("Negative interval");
    }
}
