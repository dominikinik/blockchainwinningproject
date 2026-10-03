package com.example.monitor.application;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.monitor.domain.DowntimePublisher;
import com.example.monitor.domain.DowntimeReport;
import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.ServiceTracking;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEventStore;
import com.example.monitor.domain.TrackingEventStore.ConcurrencyException;
import com.example.monitor.domain.TrackingException;
import com.example.monitor.domain.TrackingSummary;

/**
 * Use cases of the monitor: subscribe, unsubscribe, check, and read. Every command loads the aggregate
 * from its events, runs on it, and appends the new events with an optimistic version check (retrying on
 * a conflict). After each stored event that triggers a report, all of the service's events are loaded
 * again, aggregated, and the resulting downtime is sent to the {@link DowntimePublisher}.
 */
public class TrackingService {

	private static final Logger log = LoggerFactory.getLogger(TrackingService.class);

	private static final int MAX_ATTEMPTS = 5;

	private final TrackingEventStore store;

	private final HealthProbe probe;

	private final DowntimePublisher publisher;

	private final Clock clock;

	private final Duration checkInterval;

	public TrackingService(TrackingEventStore store, HealthProbe probe, DowntimePublisher publisher, Clock clock,
			Duration checkInterval) {
		this.store = store;
		this.probe = probe;
		this.publisher = publisher;
		this.clock = clock;
		this.checkInterval = checkInterval;
	}

	/**
	 * Starts tracking a service and records {@code TrackingStarted}.
	 *
	 * @param healthUrl absolute http(s) URL of the service's health endpoint
	 * @param requested the service UUID to track under, or {@code null} for a new one
	 * @return the service's id
	 * @throws IllegalArgumentException        if the URL isn't an absolute http(s) URL
	 * @throws TrackingException.AlreadyActive if that service is already tracked
	 */
	public ServiceId subscribe(String healthUrl, ServiceId requested) {
		String url = validateUrl(healthUrl);
		ServiceId id = requested != null ? requested : ServiceId.newId();
		execute(id, tracking -> tracking.start(url, checkInterval, clock.instant()));
		log.info("Tracking {} at {}", id, url);
		return id;
	}

	/**
	 * Stops tracking a service, records {@code TrackingFinished} and publishes its downtime.
	 *
	 * @throws TrackingNotFoundException  if the service was never tracked
	 * @throws TrackingException.NotActive if it is no longer tracked
	 */
	public void unsubscribe(ServiceId id) {
		execute(id, tracking -> {
			requireExists(tracking);
			tracking.finish(clock.instant());
		});
		log.info("Stopped tracking {}", id);
	}

	/**
	 * Checks one service's health and records the outcome. Does nothing if it isn't tracked, including
	 * when it is unsubscribed while the call is in flight.
	 */
	public void check(ServiceId id) {
		ServiceTracking current = load(id);
		if (!current.isActive()) {
			return;
		}
		Instant at = clock.instant();
		HealthCheckResult result = probe.check(current.healthUrl());
		try {
			execute(id, tracking -> tracking.recordCheck(result, at));
		}
		catch (TrackingException.NotActive e) {
			log.debug("Dropped the check of {}: unsubscribed meanwhile", id);
		}
	}

	/** Checks every tracked service, concurrently, and waits for all of them. */
	public void checkActive() {
		List<ServiceId> active = store.serviceIds().stream().filter(id -> load(id).isActive()).toList();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (ServiceId id : active) {
				executor.submit(() -> {
					try {
						check(id);
					}
					catch (RuntimeException e) {
						log.warn("Check of {} failed", id, e);
					}
				});
			}
		}
	}

	/** @throws TrackingNotFoundException if the service was never tracked */
	public TrackingSummary get(ServiceId id) {
		return requireExists(load(id)).summary();
	}

	/** Every service that was ever tracked. */
	public List<TrackingSummary> list() {
		return store.serviceIds().stream().map(id -> load(id).summary()).toList();
	}

	/** @throws TrackingNotFoundException if the service was never tracked */
	public List<TrackingEvent> events(ServiceId id) {
		List<TrackingEvent> events = store.load(id);
		if (events.isEmpty()) {
			throw new TrackingNotFoundException(id);
		}
		return events;
	}

	private void execute(ServiceId id, Consumer<ServiceTracking> command) {
		for (int attempt = 1;; attempt++) {
			ServiceTracking tracking = load(id);
			command.accept(tracking);
			long expected = tracking.version();
			List<TrackingEvent> events = tracking.pullPendingEvents();
			if (events.isEmpty()) {
				return;
			}
			try {
				store.append(id, expected, events);
			}
			catch (ConcurrencyException e) {
				if (attempt >= MAX_ATTEMPTS) {
					throw e;
				}
				continue;
			}
			for (int i = 0; i < events.size(); i++) {
				if (events.get(i).triggersDowntimeReport()) {
					publishDowntime(id, (int) expected + i);
				}
			}
			return;
		}
	}

	/**
	 * Aggregates every event of the service up to the trigger and sends the resulting downtime.
	 *
	 * @param position the trigger's index in the service's stream
	 */
	private void publishDowntime(ServiceId id, int position) {
		List<TrackingEvent> history = store.load(id).subList(0, position + 1);
		DowntimeReport report = ServiceTracking.rehydrate(id, history).report(history.getLast());
		try {
			publisher.publish(report);
		}
		catch (RuntimeException e) {
			log.warn("Could not publish the downtime of {} after {}: {}", report.serviceId(), report.trigger(),
					e.getMessage());
		}
	}

	private ServiceTracking load(ServiceId id) {
		return ServiceTracking.rehydrate(id, store.load(id));
	}

	private static ServiceTracking requireExists(ServiceTracking tracking) {
		if (!tracking.exists()) {
			throw new TrackingNotFoundException(tracking.id());
		}
		return tracking;
	}

	private static String validateUrl(String healthUrl) {
		if (healthUrl == null || healthUrl.isBlank()) {
			throw new IllegalArgumentException("healthUrl is required");
		}
		String url = healthUrl.trim();
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("healthUrl is not a valid URL: " + healthUrl);
		}
		if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
				|| uri.getHost() == null) {
			throw new IllegalArgumentException("healthUrl must be an absolute http(s) URL: " + healthUrl);
		}
		return url;
	}

}
