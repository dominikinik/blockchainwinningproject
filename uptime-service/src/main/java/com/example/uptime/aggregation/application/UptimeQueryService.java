package com.example.uptime.aggregation.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.uptime.UptimeProperties;
import com.example.uptime.aggregation.domain.TimeRange;
import com.example.uptime.aggregation.domain.UptimeWindowPolicy;
import com.example.uptime.tracking.application.TrackingCoverage;
import com.example.uptime.tracking.domain.TrackingSession;

@Service
@Transactional(readOnly = true)
public class UptimeQueryService {
	private final UptimeHistoryReader reader;
	private final TrackingCoverage coverage;
	private final UptimeProperties properties;
	private final Clock clock;

	public UptimeQueryService(UptimeHistoryReader reader, TrackingCoverage coverage,
			UptimeProperties properties, Clock clock) {
		this.reader = reader;
		this.coverage = coverage;
		this.properties = properties;
		this.clock = clock;
	}

	public UptimePoint at(Instant time) {
		return point(event(time));
	}

	public UptimeEventDetails event(Instant time) {
		Instant now = clock.instant();
		validateFuture(time, now);
		TrackingSession session = covered(time, time, now).stream()
				.filter(s -> contains(s, time, now)).findFirst().orElseThrow();
		return reader.at(time).stream()
				.filter(e -> e.sessionId().equals(session.id()) && !time.isBefore(e.windowStart())
						&& time.isBefore(e.windowEnd()))
				.findFirst().map(this::details)
				.orElseGet(() -> missing(session, UptimeWindowPolicy.bucketStart(time), now));
	}

	/** Inclusive original bounds; session and event windows remain half-open. */
	public List<UptimePoint> range(Instant from, Instant to) {
		Instant now = clock.instant();
		if (from != null) validateFuture(from, now);
		if (to != null) validateFuture(to, now);
		if (from != null && to != null && from.isAfter(to)) {
			throw new IllegalArgumentException("'from' must not be after 'to'");
		}
		// Validate explicit endpoints before defaults can invert or mask an untracked request.
		if (from != null) covered(from, from, now);
		List<TrackingSession> endSessions = to == null ? List.of() : covered(to, to, now);
		TrackingSession latest = null;
		if (from == null && to != null) {
			latest = endSessions.stream().filter(s -> contains(s, to, now)).findFirst().orElseThrow();
		} else if (from == null || to == null) {
			latest = coverage.latest().orElseThrow(() -> new IllegalArgumentException("No tracking history is available"));
		}
		Instant end = to;
		if (end == null) {
			Instant limit = min(endOf(latest, now), now.plusNanos(1));
			if (latest.committedThrough() == null) {
				throw new IllegalArgumentException("No committed tracking history is available");
			}
			limit = min(limit, latest.committedThrough());
			if (!limit.isAfter(latest.startedAt())) {
				throw new IllegalArgumentException("No committed tracking history is available");
			}
			end = limit.minusNanos(1);
		}
		// Range configuration remains in seconds, independently of parent window duration.
		Instant start = from;
		if (start == null) {
			start = max(latest.startedAt(), end.truncatedTo(ChronoUnit.SECONDS)
					.minusSeconds(properties.defaultRangeSeconds() - 1));
		}
		if (start.isAfter(end)) throw new IllegalArgumentException("'from' must not be after 'to'");
		long seconds = ChronoUnit.SECONDS.between(start.truncatedTo(ChronoUnit.SECONDS),
				end.truncatedTo(ChronoUnit.SECONDS)) + 1;
		if (seconds > properties.maxRangeSeconds()) {
			throw new IllegalArgumentException("Range exceeds maximum of " + properties.maxRangeSeconds() + "s");
		}
		List<TrackingSession> sessions = covered(start, end, now);
		Map<UUID, Map<Instant, List<UptimeHistoryEntry>>> entries = reader.range(start, end).stream()
				.collect(Collectors.groupingBy(UptimeHistoryEntry::sessionId,
						Collectors.groupingBy(e -> UptimeWindowPolicy.bucketStart(e.bucketStart()))));
		List<UptimePoint> points = new ArrayList<>();
		for (TrackingSession session : sessions) {
			Instant first = max(start, session.startedAt());
			Instant last = min(end, endOf(session, now).minusNanos(1));
			if (first.isAfter(last)) continue;
			for (Instant bucket = UptimeWindowPolicy.bucketStart(first); !bucket.isAfter(last);
					bucket = UptimeWindowPolicy.end(bucket)) {
				Instant b = bucket;
				List<UptimeHistoryEntry> parents = entries.getOrDefault(session.id(), Map.of())
						.getOrDefault(b, List.of()).stream()
						.filter(e -> !e.windowStart().isAfter(last) && e.windowEnd().isAfter(first))
						.sorted(Comparator.comparing(UptimeHistoryEntry::windowStart)).toList();
				if (parents.isEmpty()) points.add(point(missing(session, bucket, now)));
				else parents.forEach(e -> points.add(point(summary(e))));
			}
		}
		points.sort(Comparator.comparing(UptimePoint::windowStart).thenComparing(UptimePoint::sessionId));
		return List.copyOf(points);
	}

	public record MeasuredSeconds(long upSeconds, long totalSeconds) {}

	public static class HistoryNotReadyException extends IllegalStateException {
		public HistoryNotReadyException() {
			super("Tracking history is not committed yet");
		}
	}

	/** Deal windows are half-open whole seconds; unknown evidence is never counted as healthy. */
	public MeasuredSeconds measureSeconds(Instant from, Instant end) {
		if (from == null || end == null || !end.isAfter(from)
				|| from.getNano() != 0 || end.getNano() != 0) {
			throw new IllegalArgumentException("Measurement requires a positive whole-second window");
		}
		Instant last = end.minusNanos(1);
		List<UptimePoint> points = range(from, last);
		Instant now = clock.instant();
		for (TrackingSession session : covered(from, last, now)) {
			Instant requiredEnd = min(end, endOf(session, now));
			if (session.startedAt().isBefore(end) && endOf(session, now).isAfter(from)
					&& (session.committedThrough() == null || session.committedThrough().isBefore(requiredEnd))) {
				throw new HistoryNotReadyException();
			}
		}
		if (points.stream().anyMatch(p -> p.status().equals("PENDING"))) {
			throw new HistoryNotReadyException();
		}
		List<UptimeHistoryEntry> parents = reader.range(from, last).stream()
				.filter(e -> points.stream().anyMatch(p -> p.sessionId().equals(e.sessionId())))
				.sorted(Comparator.comparing(UptimeHistoryEntry::windowStart)).toList();
		List<TimeRange> unhealthy = new ArrayList<>();
		for (UptimeHistoryEntry parent : parents) {
			unhealthy.addAll(parent.unknownIntervals());
			reader.badEvents(parent.eventId()).forEach(b ->
					unhealthy.add(new TimeRange(b.windowStart(), b.windowEnd())));
		}
		long up = 0;
		for (Instant second = from; second.isBefore(end); second = second.plusSeconds(1)) {
			Instant secondEnd = second.plusSeconds(1);
			Instant cursor = second;
			for (UptimeHistoryEntry parent : parents) {
				if (!parent.windowEnd().isAfter(cursor)) continue;
				if (parent.windowStart().isAfter(cursor)) break;
				cursor = max(cursor, parent.windowEnd());
				if (!cursor.isBefore(secondEnd)) break;
			}
			Instant start = second;
			boolean bad = unhealthy.stream().anyMatch(r ->
					r.start().isBefore(secondEnd) && (r.end().isAfter(start)
							|| r.start().equals(r.end()) && !r.start().isBefore(start)));
			if (!cursor.isBefore(secondEnd) && !bad) up++;
		}
		return new MeasuredSeconds(up, ChronoUnit.SECONDS.between(from, end));
	}

	private List<TrackingSession> covered(Instant from, Instant to, Instant now) {
		List<TrackingSession> sessions = coverage.sessions(from, to).stream()
				.sorted(Comparator.comparing(TrackingSession::startedAt)).toList();
		Instant cursor = from;
		for (TrackingSession session : sessions) {
			Instant end = endOf(session, now);
			if (!end.isAfter(cursor)) continue;
			if (session.startedAt().isAfter(cursor)) {
				throw new OutsideTrackingCoverageException(cursor, min(to, session.startedAt()));
			}
			cursor = max(cursor, end);
			if (cursor.isAfter(to)) return sessions;
		}
		throw new OutsideTrackingCoverageException(cursor, to);
	}

	private boolean contains(TrackingSession session, Instant time, Instant now) {
		return !time.isBefore(session.startedAt()) && time.isBefore(endOf(session, now));
	}

	private Instant endOf(TrackingSession session, Instant now) {
		return session.stoppedAt() == null ? now.plusNanos(1) : session.stoppedAt();
	}

	private UptimeEventDetails missing(TrackingSession session, Instant bucket, Instant now) {
		Instant start = max(bucket, session.startedAt());
		Instant end = min(UptimeWindowPolicy.end(bucket), endOf(session, now));
		String status = session.committedThrough() == null || end.isAfter(session.committedThrough())
				? "PENDING" : "UNKNOWN";
		return new UptimeEventDetails(null, session.id(), bucket, start, end, status, 0, 0, 0,
				true, List.of(), List.of(), List.of(new TimeRange(start, end)));
	}

	private UptimeEventDetails summary(UptimeHistoryEntry e) {
		return new UptimeEventDetails(e.eventId(), e.sessionId(), e.bucketStart(), e.windowStart(), e.windowEnd(),
				e.status().name(), e.totalChecks(), e.successfulChecks(), e.totalChecks() - e.successfulChecks(),
				e.partialCoverage(), e.badEventIds(), List.of(), e.unknownIntervals());
	}

	private UptimeEventDetails details(UptimeHistoryEntry e) {
		UptimeEventDetails d = summary(e);
		return new UptimeEventDetails(d.eventId(), d.sessionId(), d.bucketStart(), d.windowStart(), d.windowEnd(),
				d.status(), d.totalChecks(), d.successfulChecks(), d.failedChecks(), d.partialCoverage(),
				d.badEventIds(), reader.badEvents(e.eventId()), d.unknownIntervals());
	}

	private UptimePoint point(UptimeEventDetails d) {
		Boolean down = switch (d.status()) {
			case "FAILED" -> true;
			case "SUCCESS" -> false;
			default -> null;
		};
		return new UptimePoint(d.bucketStart(), down, d.sessionId(), d.windowStart(), d.windowEnd(),
				d.status(), d.partialCoverage());
	}

	private void validateFuture(Instant time, Instant now) {
		if (time.isAfter(now)) throw new IllegalArgumentException("Query time must not be in the future");
	}

	private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
	private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
