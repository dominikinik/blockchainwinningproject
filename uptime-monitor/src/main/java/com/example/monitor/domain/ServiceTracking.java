package com.example.monitor.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.HealthCheckSucceeded;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;

/**
 * Aggregate root: the tracking of one service, event-sourced. Commands check the invariants and record
 * new events; state changes only by applying events, so replaying the history gives the same state.
 * <p>
 * Total downtime is {@code Downtime events × check interval}, summed over every tracking period: each
 * failed check stands for one interval of downtime. {@code InternalErrorHappened} events are counted but
 * are not downtime, since the service's own health was not what failed.
 */
public final class ServiceTracking {

	private final ServiceId id;

	private final List<TrackingEvent> pending = new ArrayList<>();

	private long version;

	private boolean active;

	private String healthUrl;

	private Duration checkInterval = Duration.ZERO;

	private Instant startedAt;

	private Instant finishedAt;

	private Duration totalDowntime = Duration.ZERO;

	private long downtimeChecks;

	private long internalErrors;

	private ServiceTracking(ServiceId id) {
		this.id = Objects.requireNonNull(id, "id");
	}

	/** A service with no events yet. */
	public static ServiceTracking newTracking(ServiceId id) {
		return new ServiceTracking(id);
	}

	/**
	 * Rebuilds a service from its stored events.
	 *
	 * @throws IllegalArgumentException if an event belongs to another service or the history doesn't start
	 *                                  with {@code TrackingStarted}
	 */
	public static ServiceTracking rehydrate(ServiceId id, List<TrackingEvent> history) {
		ServiceTracking tracking = new ServiceTracking(id);
		if (!history.isEmpty() && !(history.getFirst() instanceof TrackingStarted)) {
			throw new IllegalArgumentException("History of " + id + " must start with TrackingStarted");
		}
		for (TrackingEvent event : history) {
			if (!event.serviceId().equals(id)) {
				throw new IllegalArgumentException("Event of " + event.serviceId() + " in history of " + id);
			}
			tracking.apply(event);
			tracking.version++;
		}
		return tracking;
	}

	/**
	 * Starts tracking (subscribe). A finished service can be tracked again; its earlier downtime is kept.
	 *
	 * @throws TrackingException.AlreadyActive if it is already tracked
	 */
	public void start(String healthUrl, Duration checkInterval, Instant at) {
		if (active) {
			throw new TrackingException.AlreadyActive(id);
		}
		if (checkInterval.isNegative() || checkInterval.isZero()) {
			throw new IllegalArgumentException("Check interval must be positive");
		}
		record(new TrackingStarted(id, healthUrl, checkInterval, at));
	}

	/**
	 * Records every health-check result; healthy probes are persisted so the oracle can report UP rounds directly.
	 *
	 * @return the recorded event, or {@code null} for a healthy check
	 * @throws TrackingException.NotActive if the service isn't tracked
	 */
	public TrackingEvent recordCheck(HealthCheckResult result, Instant at) {
		requireActive();
		TrackingEvent event = switch (result.outcome()) {
			case HEALTHY -> new HealthCheckSucceeded(id, at);
			case DOWN -> new Downtime(id, result.httpStatus(), result.detail(), at);
			case INTERNAL_ERROR -> new InternalErrorHappened(id, result.httpStatus(), result.detail(), at);
		};
		if (event != null) {
			record(event);
		}
		return event;
	}

	/**
	 * Stops tracking (unsubscribe).
	 *
	 * @throws TrackingException.NotActive if the service isn't tracked
	 */
	public void finish(Instant at) {
		requireActive();
		record(new TrackingFinished(id, at));
	}

	/** The downtime report as of the given triggering event, which must already be applied. */
	public DowntimeReport report(TrackingEvent trigger) {
		return new DowntimeReport(id, trigger.type(), totalDowntime, downtimeChecks, internalErrors, active,
				trigger.occurredAt());
	}

	public TrackingSummary summary() {
		return new TrackingSummary(id, healthUrl, active, checkInterval, startedAt, finishedAt, totalDowntime,
				downtimeChecks, internalErrors, version + pending.size());
	}

	/** Returns the events recorded since loading and forgets them; the caller must store them. */
	public List<TrackingEvent> pullPendingEvents() {
		List<TrackingEvent> events = List.copyOf(pending);
		version += pending.size();
		pending.clear();
		return events;
	}

	public ServiceId id() {
		return id;
	}

	/** How many events were stored when this was loaded (plus any already pulled). */
	public long version() {
		return version;
	}

	public boolean isActive() {
		return active;
	}

	public boolean exists() {
		return version + pending.size() > 0;
	}

	public String healthUrl() {
		return healthUrl;
	}

	public Duration totalDowntime() {
		return totalDowntime;
	}

	private void requireActive() {
		if (!active) {
			throw new TrackingException.NotActive(id);
		}
	}

	private void record(TrackingEvent event) {
		apply(event);
		pending.add(event);
	}

	private void apply(TrackingEvent event) {
			switch (event) {
			case HealthCheckSucceeded e -> {
			}
			case TrackingStarted e -> {
				active = true;
				healthUrl = e.healthUrl();
				checkInterval = e.checkInterval();
				startedAt = e.occurredAt();
				finishedAt = null;
			}
			case Downtime e -> {
				downtimeChecks++;
				totalDowntime = totalDowntime.plus(checkInterval);
			}
			case InternalErrorHappened e -> internalErrors++;
			case TrackingFinished e -> {
				active = false;
				finishedAt = e.occurredAt();
			}
		}
	}

}
