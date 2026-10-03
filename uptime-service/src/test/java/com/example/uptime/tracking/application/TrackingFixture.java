package com.example.uptime.tracking.application;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.example.uptime.aggregation.application.AggregateChecks;
import com.example.uptime.aggregation.application.UptimeEventStore;
import com.example.uptime.aggregation.domain.UptimeEvent;
import com.example.uptime.checking.application.ExecuteCheck;
import com.example.uptime.checking.application.HealthProbe;
import com.example.uptime.checking.domain.CheckResult;
import com.example.uptime.checking.domain.ProbeResult;
import com.example.uptime.tracking.domain.*;

/** Shared database-free fixture; writes update the same committed frontier as the production adapter. */
public final class TrackingFixture {
	public static final Instant START = Instant.parse("2026-10-03T12:00:00Z");
	public final MutableClock clock = new MutableClock(START);
	public final HealthProbe probe = mock(HealthProbe.class);
	public final MemoryTrackingStore memory = new MemoryTrackingStore();
	public final TrackingStore lifecycle = spy(memory);
	public final UptimeEventStore store = mock(UptimeEventStore.class);
	public final List<UptimeEvent> saved = new ArrayList<>();
	public final AtomicReference<Consumer<CheckResult>> beforeAdmission = new AtomicReference<>();
	public final AggregateChecks aggregation;
	public final TrackingService tracking;

	public TrackingFixture() { this(10); }

	public TrackingFixture(int capacity) {
		when(probe.check()).thenReturn(ProbeResult.success());
		aggregation = new AggregateChecks(store, clock, 100, capacity, 10, 100, 1000, 50);
		ExecuteCheck checks = new ExecuteCheck(probe, result -> {
			Consumer<CheckResult> hook = beforeAdmission.get();
			if (hook != null) hook.accept(result);
			aggregation.accept(result);
		}, clock);
		tracking = new TrackingService(aggregation, checks, lifecycle, clock);
		doAnswer(call -> {
			commit(call.getArgument(0));
			return null;
		}).when(store).saveAll(anyList());
	}

	public synchronized void commit(List<UptimeEvent> events) {
		for (UptimeEvent event : events) {
			if (saved.stream().noneMatch(e -> e.id().equals(event.id()))) saved.add(event);
			memory.advance(event.sessionId(), event.windowEnd());
		}
	}

	public void at(long millis) { clock.set(START.plusMillis(millis)); }

	public static final class MutableClock extends Clock {
		private final AtomicReference<Instant> now;
		private final ZoneId zone;
		public MutableClock(Instant now) { this(new AtomicReference<>(now), ZoneOffset.UTC); }
		private MutableClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
		public void set(Instant value) { now.set(value); }
		@Override public Instant instant() { return now.get(); }
		@Override public ZoneId getZone() { return zone; }
		@Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
	}

	public static class MemoryTrackingStore implements TrackingStore {
		private final Map<UUID, TrackingSession> sessions = new LinkedHashMap<>();
		private final Map<UUID, TrackingEvent> events = new LinkedHashMap<>();
		@Override public synchronized void saveStart(TrackingSession session, TrackingEvent event) {
			if (!sessions.containsKey(session.id()) && sessions.values().stream()
					.anyMatch(s -> s.status() == TrackingStatus.ACTIVE || s.status() == TrackingStatus.STOPPING)) {
				throw new TrackingConflictException("Persisted session is open");
			}
			sessions.putIfAbsent(session.id(), session);
			events.putIfAbsent(event.id(), event);
		}
		@Override public synchronized void saveStop(TrackingSession session, TrackingEvent event) {
			TrackingSession prior = sessions.get(session.id());
			sessions.put(session.id(), new TrackingSession(session.id(), prior.startedAt(), session.stoppedAt(),
					TrackingStatus.STOPPING, prior.committedThrough()));
			events.putIfAbsent(event.id(), event);
		}
		public synchronized void advance(UUID id, Instant end) {
			TrackingSession prior = sessions.get(id);
			Instant committed = prior.committedThrough();
			if (committed == null || committed.isBefore(end)) {
				sessions.put(id, new TrackingSession(id, prior.startedAt(), prior.stoppedAt(), prior.status(), end));
			}
		}
		@Override public synchronized void completeStop(UUID id) {
			TrackingSession prior = sessions.get(id);
			if (prior.stoppedAt().isAfter(prior.startedAt()) && (prior.committedThrough() == null
					|| prior.committedThrough().isBefore(prior.stoppedAt()))) throw new IllegalStateException("Uncommitted tail");
			sessions.put(id, new TrackingSession(id, prior.startedAt(), prior.stoppedAt(), TrackingStatus.STOPPED, prior.committedThrough()));
		}
		@Override public synchronized Optional<TrackingSession> find(UUID id) { return Optional.ofNullable(sessions.get(id)); }
		@Override public synchronized Optional<TrackingSession> latest() {
			return sessions.values().stream().max(Comparator.comparing(TrackingSession::startedAt).thenComparing(TrackingSession::id));
		}
		@Override public synchronized List<TrackingSession> sessions(Instant from, Instant to) {
			return sessions.values().stream().filter(s -> s.overlaps(from, to)).toList();
		}
		@Override public synchronized List<TrackingEvent> events(UUID id) {
			return events.values().stream().filter(e -> e.sessionId().equals(id)).toList();
		}
		@Override public synchronized void recoverInterrupted() {
			for (TrackingSession prior : List.copyOf(sessions.values())) {
				if (prior.status() == TrackingStatus.ACTIVE || prior.status() == TrackingStatus.STOPPING) {
					Instant end = prior.committedThrough() == null ? prior.startedAt() : prior.committedThrough();
					sessions.put(prior.id(), new TrackingSession(prior.id(), prior.startedAt(), end,
							TrackingStatus.INTERRUPTED, prior.committedThrough()));
				}
			}
		}
	}
}
