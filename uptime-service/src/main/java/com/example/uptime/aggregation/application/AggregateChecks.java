package com.example.uptime.aggregation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import com.example.uptime.aggregation.domain.BadEventType;
import com.example.uptime.aggregation.domain.FailureKey;
import com.example.uptime.aggregation.domain.UptimeEvent;
import com.example.uptime.aggregation.domain.UptimeWindow;
import com.example.uptime.aggregation.domain.UptimeWindowPolicy;
import com.example.uptime.checking.application.CheckResultSink;
import com.example.uptime.checking.domain.CheckOutcome;
import com.example.uptime.checking.domain.CheckResult;
import com.example.uptime.tracking.domain.TrackingSession;
import com.example.uptime.tracking.domain.TrackingStatus;

public final class AggregateChecks implements CheckResultSink {
	private final UptimeEventStore store;
	private final Clock clock;
	private final int expectedChecksPerWindow;
	private final int maxBufferedWindows;
	private final int persistenceBatchSize;
	private final long retryInitialMs;
	private final long retryMaxMs;
	private final long maxObservationGapMs;
	private final Object stateLock = new Object();
	private final ReentrantLock persistenceLock = new ReentrantLock();
	private final TreeMap<Instant, UptimeWindow> open = new TreeMap<>();
	private final LinkedHashMap<UUID, UptimeEvent> pending = new LinkedHashMap<>();
	private final LinkedHashSet<UUID> recentIds = new LinkedHashSet<>();
	private TrackingSession session;
	private Instant stopAt;
	private Instant cursor;
	private Instant lastObserved;
	private Instant firstBadObserved;
	private UUID run;
	private FailureKey failureKey;
	private String diagnostic;
	private Instant watermark;
	private long rejectedChecks;
	private long lateChecks;
	private long outOfOrderChecks;
	private long capacityRejections;
	private int consecutivePersistenceFailures;
	private long retryDelayMs;
	private Instant nextRetryAt;
	private String lastPersistenceFailure;
	private List<UptimeEvent> retryBatch;

	public AggregateChecks(UptimeEventStore store, Clock clock, int expectedChecksPerWindow,
			int maxBufferedWindows, int persistenceBatchSize, long retryInitialMs, long retryMaxMs) {
		this(store, clock, expectedChecksPerWindow, maxBufferedWindows, persistenceBatchSize,
				retryInitialMs, retryMaxMs, 50_000);
	}

	public AggregateChecks(UptimeEventStore store, Clock clock, int expectedChecksPerWindow,
			int maxBufferedWindows, int persistenceBatchSize, long retryInitialMs, long retryMaxMs,
			long maxObservationGapMs) {
		this.store = Objects.requireNonNull(store, "store");
		this.clock = Objects.requireNonNull(clock, "clock");
		if (expectedChecksPerWindow <= 0 || maxBufferedWindows <= 0 || persistenceBatchSize <= 0
				|| retryInitialMs <= 0 || retryMaxMs < retryInitialMs || maxObservationGapMs <= 0) {
			throw new IllegalArgumentException("Counts and delays must be positive; retryMaxMs must be >= retryInitialMs");
		}
		this.expectedChecksPerWindow = expectedChecksPerWindow;
		this.maxBufferedWindows = maxBufferedWindows;
		this.persistenceBatchSize = persistenceBatchSize;
		this.retryInitialMs = retryInitialMs;
		this.retryMaxMs = retryMaxMs;
		this.maxObservationGapMs = maxObservationGapMs;
	}

	public void startSession(TrackingSession trackingSession) {
		Objects.requireNonNull(trackingSession, "trackingSession");
		synchronized (stateLock) {
			if (session != null) {
				throw new IllegalStateException("Previous session is not finalized");
			}
			if (trackingSession.status() != TrackingStatus.ACTIVE || trackingSession.stoppedAt() != null) {
				throw new IllegalArgumentException("Session must be active");
			}
			session = trackingSession;
			stopAt = null;
			cursor = trackingSession.startedAt();
			watermark = cursor;
			lastObserved = null;
			firstBadObserved = null;
			failureKey = null;
			run = null;
			diagnostic = null;
			recentIds.clear();
		}
	}

	public boolean hasActiveSession() {
		synchronized (stateLock) {
			return session != null && stopAt == null;
		}
	}

	public boolean hasUnfinalizedSession() {
		synchronized (stateLock) {
			return session != null;
		}
	}

	public void stopSession(Instant stoppedAt) {
		Objects.requireNonNull(stoppedAt, "stoppedAt");
		synchronized (stateLock) {
			if (stopAt != null) {
				if (!stopAt.equals(stoppedAt)) {
					throw new IllegalArgumentException("Session stop cutoff is immutable");
				}
			} else {
				if (session == null) {
					throw new IllegalStateException("No active session");
				}
				if (stoppedAt.isBefore(cursor)) {
					throw new IllegalArgumentException("Stop precedes admitted or completed time");
				}
				// Capture intent before any capacity-limited work; admission stops immediately.
				stopAt = stoppedAt;
			}
			if (session != null) {
				finalizeThrough(stopAt);
			}
		}
	}

	@Override
	public void accept(CheckResult result) {
		Objects.requireNonNull(result, "result");
		synchronized (stateLock) {
			if (session == null || stopAt != null) {
				rejectedChecks++;
				throw new CheckAdmissionException("No active tracking session");
			}
			if (recentIds.contains(result.checkId())
					|| open.values().stream().anyMatch(window -> window.contains(result.checkId()))) {
				return;
			}
			if (result.observedAt().isBefore(watermark)) {
				rejectedChecks++;
				lateChecks++;
				throw new CheckAdmissionException("Observation precedes session finalization watermark " + watermark);
			}
			if (result.observedAt().isBefore(cursor)) {
				rejectedChecks++;
				outOfOrderChecks++;
				throw new CheckAdmissionException("Out-of-order observation");
			}
			ensureAdmissionCapacity(result.observedAt());
			while (cursor.isBefore(result.observedAt())) {
				advanceOneSegment(result.observedAt());
			}
			FailureKey next = null;
			if (result.outcome() == CheckOutcome.FAILURE) {
				var failure = result.failure();
				next = new FailureKey(BadEventType.valueOf(failure.type().name()), failure.code(), failure.reason());
			}
			boolean continuous = lastObserved != null
					&& !result.observedAt().isAfter(lastObserved.plusMillis(maxObservationGapMs));
			if (next != null && (!continuous || !next.equals(failureKey))) {
				run = UUID.randomUUID();
				firstBadObserved = result.observedAt();
				diagnostic = result.failure().message();
			}
			failureKey = next;
			lastObserved = result.observedAt();
			window(result.observedAt()).observe(result, run, failureKey, firstBadObserved);
			recentIds.add(result.checkId());
			// A bounded replay cache supplements IDs retained by open windows.
			if (recentIds.size() > Math.max(1024L, (long) maxBufferedWindows * expectedChecksPerWindow)) {
				recentIds.remove(recentIds.getFirst());
			}
		}
	}

	public void completeBefore(Instant cutoff) {
		Instant boundary = UptimeWindowPolicy.bucketStart(Objects.requireNonNull(cutoff, "cutoff"));
		synchronized (stateLock) {
			if (session != null) {
				// A stopped session always drains to its exact cutoff, not the current wall clock.
				finalizeThrough(stopAt != null ? stopAt : boundary);
			}
		}
	}

	private void finalizeThrough(Instant target) {
		closeElapsedWindows(target);
		while (cursor.isBefore(target)) {
			Instant bucket = UptimeWindowPolicy.bucketStart(cursor);
			if (!open.containsKey(bucket) && open.size() + pending.size() >= maxBufferedWindows) {
				return;
			}
			advanceOneSegment(target);
			closeElapsedWindows(target);
		}
		if (stopAt != null && !cursor.isBefore(stopAt) && open.isEmpty()) {
			session = null;
		}
	}

	private void closeElapsedWindows(Instant target) {
		var iterator = open.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			Instant end = UptimeWindowPolicy.end(entry.getKey());
			if (stopAt != null && end.isAfter(stopAt)) {
				end = stopAt;
			}
			if (end.isAfter(cursor) || end.isAfter(target)) {
				break;
			}
			UptimeEvent event = entry.getValue().close(end);
			pending.put(event.id(), event);
			iterator.remove();
			if (end.isAfter(watermark)) {
				watermark = end;
			}
		}
	}

	private UptimeWindow window(Instant at) {
		Instant bucket = UptimeWindowPolicy.bucketStart(at);
		return open.computeIfAbsent(bucket, key -> new UptimeWindow(session.id(), key,
				session.startedAt().isAfter(key) ? session.startedAt() : key, expectedChecksPerWindow));
	}

	private void ensureAdmissionCapacity(Instant end) {
		Instant firstBucket = UptimeWindowPolicy.bucketStart(cursor);
		Instant lastBucket = UptimeWindowPolicy.bucketStart(end);
		long needed = Duration.between(firstBucket, lastBucket).getSeconds() / UptimeWindowPolicy.SECONDS + 1;
		long existing = open.keySet().stream()
				.filter(key -> !key.isBefore(firstBucket) && !key.isAfter(lastBucket)).count();
		if (needed - existing + open.size() + pending.size() > maxBufferedWindows) {
			rejectedChecks++;
			capacityRejections++;
			throw new CheckAdmissionException("Cannot admit observation: maxBufferedWindows="
					+ maxBufferedWindows + " reached; complete elapsed windows and persist pending events");
		}
	}

	private void advanceOneSegment(Instant end) {
		Instant next = UptimeWindowPolicy.end(UptimeWindowPolicy.bucketStart(cursor));
		if (next.isAfter(end)) {
			next = end;
		}
		Instant expires = lastObserved == null ? cursor : lastObserved.plusMillis(maxObservationGapMs);
		boolean known = lastObserved != null && cursor.isBefore(expires);
		if (known && next.isAfter(expires)) {
			next = expires;
		}
		window(cursor).cover(cursor, next, run, failureKey, firstBadObserved, lastObserved, diagnostic, known);
		cursor = next;
	}

	public void persistPending() {
		if (!persistenceLock.tryLock()) {
			return;
		}
		try {
			List<UptimeEvent> batch;
			synchronized (stateLock) {
				if (pending.isEmpty() || (nextRetryAt != null && clock.instant().isBefore(nextRetryAt))) {
					return;
				}
				batch = retryBatch != null ? retryBatch
						: pending.values().stream()
								.sorted(Comparator.comparing(UptimeEvent::windowStart)
										.thenComparing(UptimeEvent::sessionId).thenComparing(UptimeEvent::id))
								.limit(persistenceBatchSize).toList();
			}
			// No admission lock is held during persistence; retained events still consume capacity.
			try {
				store.saveAll(batch);
			} catch (RuntimeException failure) {
				synchronized (stateLock) {
					retryBatch = batch;
					consecutivePersistenceFailures++;
					retryDelayMs = retryDelayMs == 0 ? retryInitialMs
							: (retryDelayMs >= retryMaxMs - retryDelayMs ? retryMaxMs : retryDelayMs * 2);
					nextRetryAt = clock.instant().plusMillis(retryDelayMs);
					lastPersistenceFailure = failure.getClass().getName();
				}
				throw failure;
			}
			synchronized (stateLock) {
				for (UptimeEvent event : batch) {
					pending.remove(event.id(), event);
				}
				retryBatch = null;
				consecutivePersistenceFailures = 0;
				retryDelayMs = 0;
				nextRetryAt = null;
				lastPersistenceFailure = null;
			}
		} finally {
			persistenceLock.unlock();
		}
	}

	public AggregationStatus status() {
		synchronized (stateLock) {
			return new AggregationStatus(open.size(), pending.size(), rejectedChecks, lateChecks,
					consecutivePersistenceFailures, nextRetryAt, lastPersistenceFailure,
					outOfOrderChecks, capacityRejections, session != null && stopAt == null);
		}
	}
}
