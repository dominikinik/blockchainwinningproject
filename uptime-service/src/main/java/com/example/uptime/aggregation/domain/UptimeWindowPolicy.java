package com.example.uptime.aggregation.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** UTC minute boundaries shared by aggregation, validation and query assembly. */
public final class UptimeWindowPolicy {
    public static final long SECONDS = 60;
    public static final long MILLIS = 60_000;

    private UptimeWindowPolicy() { }

    public static Instant bucketStart(Instant time) {
        return time.truncatedTo(ChronoUnit.MINUTES);
    }

    public static Instant end(Instant bucketStart) {
        return bucketStart.plusSeconds(SECONDS);
    }
}
