package com.example.monitor.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * A read-only snapshot of a {@link ServiceTracking}.
 *
 * @param startedAt  when the latest tracking period started
 * @param finishedAt when the latest tracking period finished, or {@code null} while active
 * @param eventCount how many events the service has
 */
public record TrackingSummary(ServiceId serviceId, String healthUrl, boolean active, Duration checkInterval,
		Instant startedAt, Instant finishedAt, Duration totalDowntime, long downtimeChecks, long internalErrors,
		long eventCount) {
}
