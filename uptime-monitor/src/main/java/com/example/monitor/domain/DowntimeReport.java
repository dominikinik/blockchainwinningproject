package com.example.monitor.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The service's downtime as calculated from all of its events, sent to the blockchain after every
 * event that {@linkplain TrackingEvent#triggersDowntimeReport() triggers} a report.
 *
 * @param trigger        the event type that caused this report
 * @param totalDowntime  the downtime over every tracking period of the service
 * @param downtimeChecks how many {@code Downtime} events were recorded
 * @param internalErrors how many {@code InternalErrorHappened} events were recorded
 * @param active         whether the service is still being tracked
 * @param reportedAt     when the triggering event happened
 */
public record DowntimeReport(ServiceId serviceId, String trigger, Duration totalDowntime, long downtimeChecks,
		long internalErrors, boolean active, Instant reportedAt) {
}
