package com.example.monitor.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Something that happened to the tracking of one service. Events are the only source of truth:
 * {@link ServiceTracking} is rebuilt from them, never stored itself.
 */
public sealed interface TrackingEvent {

	ServiceId serviceId();

	Instant occurredAt();

	/** The event name, e.g. {@code Downtime}. */
	default String type() {
		return getClass().getSimpleName();
	}

	/** Whether this event makes the service's downtime be recalculated and sent to the blockchain. */
	default boolean triggersDowntimeReport() {
		return !(this instanceof TrackingStarted) && !(this instanceof HealthCheckSucceeded);
	}

	/** A completed healthy probe. Persisted as an observation and sent to the chain without a DB-history lookup. */
	record HealthCheckSucceeded(ServiceId serviceId, Instant occurredAt) implements TrackingEvent {
	}

	/**
	 * Tracking began (the subscription); always the first event of a service.
	 *
	 * @param healthUrl     the health endpoint that gets checked
	 * @param checkInterval how often it is checked; each {@link Downtime} counts as this much downtime
	 */
	record TrackingStarted(ServiceId serviceId, String healthUrl, Duration checkInterval, Instant occurredAt)
			implements TrackingEvent {
	}

	/**
	 * A check found the service down: it answered 200 with a DOWN status, or 404.
	 *
	 * @param httpStatus the status the service answered with
	 * @param reason     what the check saw
	 */
	record Downtime(ServiceId serviceId, int httpStatus, String reason, Instant occurredAt) implements TrackingEvent {
	}

	/**
	 * A check failed in any other way: another HTTP status, a timeout, or no connection.
	 *
	 * @param httpStatus the status the service answered with, or {@code null} if it didn't answer
	 * @param reason     what went wrong
	 */
	record InternalErrorHappened(ServiceId serviceId, Integer httpStatus, String reason, Instant occurredAt)
			implements TrackingEvent {
	}

	/** Tracking stopped (the unsubscription); no more checks run until a new {@link TrackingStarted}. */
	record TrackingFinished(ServiceId serviceId, Instant occurredAt) implements TrackingEvent {
	}

}
