package com.example.uptime.tracking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.example.uptime.aggregation.application.AggregateChecks;
import com.example.uptime.checking.application.ExecuteCheck;
import com.example.uptime.tracking.domain.TrackingEvent;
import com.example.uptime.tracking.domain.TrackingEventType;
import com.example.uptime.tracking.domain.TrackingSession;
import com.example.uptime.tracking.domain.TrackingStatus;

/** Coordinates user lifecycle commands without making health failures lifecycle transitions. */
public class TrackingService implements TrackingCoverage {
	private final AggregateChecks aggregation;
	private final ExecuteCheck checks;
	private final TrackingStore store;
	private final Clock clock;
	private final Object admissionGate = new Object();
	private final Object lifecycleGate = new Object();
	private TrackingSession current;
	private TrackingSession startAttempt;
	private TrackingEvent startEvent;
	private TrackingEvent stopEvent;
	private volatile String lifecycleFailure;

	public TrackingService(AggregateChecks aggregation, ExecuteCheck checks, TrackingStore store, Clock clock) {
		this.aggregation = Objects.requireNonNull(aggregation);
		this.checks = Objects.requireNonNull(checks);
		this.store = Objects.requireNonNull(store);
		this.clock = Objects.requireNonNull(clock);
	}

	public TrackingSession start() {
		synchronized (lifecycleGate) {
			synchronized (admissionGate) {
				if (current != null && (current.status() == TrackingStatus.ACTIVE || current.status() == TrackingStatus.STOPPING)
						|| aggregation.hasUnfinalizedSession() || aggregation.status().pendingEvents() > 0) {
					throw new TrackingConflictException("Tracking is active or the previous stop is still being committed");
				}
			}
			if (startAttempt == null) {
				Instant now = clock.instant();
				Optional<TrackingSession> previous = store.latest();
				if (previous.isPresent()) {
					TrackingSession latest = previous.get();
					if (latest.stoppedAt() == null || latest.status() == TrackingStatus.STOPPING) {
						throw new TrackingConflictException("A persisted tracking session is still open");
					}
					if (now.isBefore(latest.stoppedAt())) {
						throw new TrackingConflictException("The clock precedes the previous tracking stop");
					}
				}
				startAttempt = new TrackingSession(UUID.randomUUID(), now, null, TrackingStatus.ACTIVE, null);
				startEvent = new TrackingEvent(UUID.randomUUID(), startAttempt.id(), TrackingEventType.START, now, "USER_REQUEST");
			}
			try {
				// Keep identities after an ambiguous failure so a repeated start retries the same event.
				store.saveStart(startAttempt, startEvent);
			}
			catch (RuntimeException failure) {
				lifecycleFailure = failure.getClass().getSimpleName();
				throw failure;
			}
			synchronized (admissionGate) {
				aggregation.startSession(startAttempt);
				current = startAttempt;
				startAttempt = null;
				startEvent = null;
				lifecycleFailure = null;
				return current;
			}
		}
	}

	public TrackingSession stop() {
		synchronized (lifecycleGate) {
			synchronized (admissionGate) {
				if (current == null || current.status() != TrackingStatus.ACTIVE) {
					throw new TrackingConflictException("Tracking is not active");
				}
				Instant cutoff = clock.instant();
				aggregation.stopSession(cutoff);
				current = new TrackingSession(current.id(), current.startedAt(), cutoff,
						TrackingStatus.STOPPING, current.committedThrough());
				stopEvent = new TrackingEvent(UUID.randomUUID(), current.id(), TrackingEventType.STOP, cutoff, "USER_REQUEST");
			}
			try {
				persistStopIntent();
			}
			catch (RuntimeException failure) {
				// The stop cutoff is already accepted; expose STOPPING and retry, never resume admission.
				lifecycleFailure = failure.getClass().getSimpleName();
			}
			return snapshot();
		}
	}

	public void sample() {
		synchronized (admissionGate) {
			if (current != null && current.status() == TrackingStatus.ACTIVE) {
				checks.execute();
			}
		}
	}

	public void flush() {
		synchronized (admissionGate) {
			aggregation.completeBefore(clock.instant());
		}
		synchronized (lifecycleGate) {
			try {
				persistStopIntent();
				aggregation.persistPending();
				TrackingSession session = snapshot();
				if (session != null && session.status() == TrackingStatus.STOPPING
						&& !aggregation.hasUnfinalizedSession() && aggregation.status().pendingEvents() == 0) {
					store.completeStop(session.id());
					TrackingSession committed = store.find(session.id()).orElseThrow();
					synchronized (admissionGate) {
						current = committed;
					}
				}
				if (startAttempt == null) lifecycleFailure = null;
			}
			catch (RuntimeException failure) {
				lifecycleFailure = failure.getClass().getSimpleName();
				throw failure;
			}
		}
	}

	private void persistStopIntent() {
		if (stopEvent != null) {
			store.saveStop(snapshot(), stopEvent);
			stopEvent = null;
		}
	}

	public Optional<TrackingSession> state() {
		TrackingSession session = snapshot();
		if (session == null) return store.latest();
		return Optional.of(withProgress(session, store.find(session.id()).orElse(null)));
	}

	@Override
	public Optional<TrackingSession> latest() {
		return state();
	}

	@Override
	public List<TrackingSession> sessions(Instant from, Instant to) {
		List<TrackingSession> sessions = new ArrayList<>(store.sessions(from, to));
		TrackingSession session = snapshot();
		if (session != null) {
			TrackingSession persisted = sessions.stream().filter(s -> s.id().equals(session.id())).findFirst().orElse(null);
			sessions.removeIf(s -> s.id().equals(session.id()));
			// The in-memory stop cutoff takes precedence even while its write is pending.
			if (session.overlaps(from, to)) sessions.add(withProgress(session, persisted));
		}
		sessions.sort(Comparator.comparing(TrackingSession::startedAt).thenComparing(TrackingSession::id));
		return List.copyOf(sessions);
	}

	public List<TrackingEvent> events(UUID sessionId) {
		synchronized (lifecycleGate) {
			List<TrackingEvent> events = new ArrayList<>(store.events(sessionId));
			if (stopEvent != null && stopEvent.sessionId().equals(sessionId)
					&& events.stream().noneMatch(e -> e.id().equals(stopEvent.id()))) {
				events.add(stopEvent);
			}
			events.sort(Comparator.comparing(TrackingEvent::occurredAt).thenComparing(TrackingEvent::type));
			return List.copyOf(events);
		}
	}

	public String lifecycleFailure() {
		return lifecycleFailure;
	}

	private TrackingSession snapshot() {
		synchronized (admissionGate) {
			return current;
		}
	}

	private TrackingSession withProgress(TrackingSession local, TrackingSession persisted) {
		return persisted == null ? local : new TrackingSession(local.id(), local.startedAt(), local.stoppedAt(),
				local.status(), persisted.committedThrough());
	}
}
