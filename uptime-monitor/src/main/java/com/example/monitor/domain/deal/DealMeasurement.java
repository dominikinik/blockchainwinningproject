package com.example.monitor.domain.deal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;

/**
 * A deal window measured from the events of its service, as of {@code now}. All values are whole seconds.
 * <ul>
 * <li>{@code covered}: seconds of the elapsed window ({@code [start, min(end, now))}) during which the service
 * was being tracked.</li>
 * <li>Each {@code Downtime} that has happened inside the window marks one check interval from its time as down
 * ({@code [t, t + interval)}, cut at the window end; overlapping intervals count once). {@code down} is the part
 * of that inside the elapsed window (capped at {@code covered}), {@code downAhead} the part still to come.</li>
 * <li>{@code upSoFar = covered − down}: untracked time is never up.</li>
 * <li>{@code remaining}: seconds of the window still to come.</li>
 * <li>{@code bestCaseUp = upSoFar + remaining − downAhead}: the most up seconds the deal can still reach.</li>
 * </ul>
 */
public record DealMeasurement(long total, long covered, long down, long upSoFar, long remaining, long downAhead) {

	/** A measurement with no downtime ahead. */
	public DealMeasurement(long total, long covered, long down, long upSoFar, long remaining) {
		this(total, covered, down, upSoFar, remaining, 0);
	}

	public long bestCaseUp() {
		return Math.max(0, upSoFar + remaining - downAhead);
	}

	/**
	 * Measures a window.
	 *
	 * @param history all events of the service, oldest first
	 * @param start   window start (inclusive)
	 * @param total   window length in seconds
	 * @param now     the current time; events after it are ignored
	 */
	public static DealMeasurement of(List<TrackingEvent> history, Instant start, long total, Instant now) {
		Instant end = start.plusSeconds(total);
		Instant elapsedEnd = now.isBefore(end) ? (now.isBefore(start) ? start : now) : end;
		long coveredMillis = 0;
		Instant periodStart = null;
		Duration interval = Duration.ZERO;
		List<Instant[]> downs = new ArrayList<>();
		for (TrackingEvent event : history) {
			if (event.occurredAt().isAfter(now)) {
				break;
			}
			switch (event) {
				case TrackingStarted e -> {
					periodStart = e.occurredAt();
					interval = e.checkInterval();
				}
				case TrackingFinished e -> {
					if (periodStart != null) {
						coveredMillis += overlapMillis(periodStart, e.occurredAt(), start, elapsedEnd);
						periodStart = null;
					}
				}
				case Downtime e -> {
					Instant at = e.occurredAt();
					if (!at.isBefore(start) && at.isBefore(end)) {
						Instant downEnd = at.plus(interval);
						downs.add(new Instant[] { at, downEnd.isBefore(end) ? downEnd : end });
					}
				}
				default -> {
				}
			}
		}
		if (periodStart != null) {
			coveredMillis += overlapMillis(periodStart, elapsedEnd, start, elapsedEnd);
		}
		long downElapsedMillis = 0;
		long downAheadMillis = 0;
		for (Instant[] d : merge(downs)) {
			downElapsedMillis += overlapMillis(d[0], d[1], start, elapsedEnd);
			downAheadMillis += overlapMillis(d[0], d[1], elapsedEnd, end);
		}
		long covered = Math.min(total, coveredMillis / 1000);
		long down = Math.min(covered, ceilSeconds(downElapsedMillis));
		long remaining = Math.min(Duration.between(elapsedEnd, end).toMillis() / 1000, total - covered);
		long downAhead = Math.min(remaining, ceilSeconds(downAheadMillis));
		return new DealMeasurement(total, covered, down, covered - down, remaining, downAhead);
	}

	/** Joins overlapping {@code [from, to)} intervals. */
	private static List<Instant[]> merge(List<Instant[]> intervals) {
		List<Instant[]> sorted = new ArrayList<>(intervals);
		sorted.sort(Comparator.comparing(i -> i[0]));
		List<Instant[]> merged = new ArrayList<>();
		for (Instant[] i : sorted) {
			Instant[] last = merged.isEmpty() ? null : merged.getLast();
			if (last != null && !i[0].isAfter(last[1])) {
				if (i[1].isAfter(last[1])) {
					last[1] = i[1];
				}
			}
			else {
				merged.add(new Instant[] { i[0], i[1] });
			}
		}
		return merged;
	}

	private static long ceilSeconds(long millis) {
		return (millis + 999) / 1000;
	}

	private static long overlapMillis(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
		Instant from = aStart.isAfter(bStart) ? aStart : bStart;
		Instant to = aEnd.isBefore(bEnd) ? aEnd : bEnd;
		return to.isAfter(from) ? Duration.between(from, to).toMillis() : 0;
	}

}
