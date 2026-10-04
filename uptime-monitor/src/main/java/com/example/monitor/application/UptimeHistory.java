package com.example.monitor.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;
import com.example.monitor.domain.TrackingEventStore;

/**
 * Read model: a service's uptime per second, derived from its events. A second is down when the service
 * wasn't being tracked during it, or when it falls in the check interval that starts at a {@code Downtime}
 * event ({@code [t, t + interval)}), the same rule the deal measurement uses.
 */
public class UptimeHistory {

	/** @param down whether the second counts as down */
	public record Point(Instant time, boolean down) {
	}

	private final TrackingEventStore events;

	private final Clock clock;

	private final long defaultRangeSeconds;

	private final long maxRangeSeconds;

	public UptimeHistory(TrackingEventStore events, Clock clock, long defaultRangeSeconds, long maxRangeSeconds) {
		this.events = events;
		this.clock = clock;
		this.defaultRangeSeconds = defaultRangeSeconds;
		this.maxRangeSeconds = maxRangeSeconds;
	}

	/**
	 * One point per second in {@code [from, to]}, both inclusive and truncated to seconds. {@code to} defaults to
	 * now and {@code from} to {@code defaultRangeSeconds} before it.
	 *
	 * @throws IllegalArgumentException if {@code from} is after {@code to} or the range is too long
	 */
	public List<Point> range(ServiceId service, Instant from, Instant to) {
		Instant end = (to != null ? to : clock.instant()).truncatedTo(ChronoUnit.SECONDS);
		Instant start = (from != null ? from : end.minusSeconds(defaultRangeSeconds - 1)).truncatedTo(ChronoUnit.SECONDS);
		if (start.isAfter(end)) {
			throw new IllegalArgumentException("'from' must not be after 'to'");
		}
		long seconds = ChronoUnit.SECONDS.between(start, end) + 1;
		if (seconds > maxRangeSeconds) {
			throw new IllegalArgumentException("Range of " + seconds + "s exceeds maximum of " + maxRangeSeconds + "s");
		}
		boolean[] up = new boolean[(int) seconds];
		Instant periodStart = null;
		Duration interval = Duration.ZERO;
		List<Instant[]> downs = new ArrayList<>();
		for (TrackingEvent event : events.load(service)) {
			switch (event) {
				case TrackingStarted e -> {
					periodStart = e.occurredAt();
					interval = e.checkInterval();
				}
				case TrackingFinished e -> {
					mark(up, start, periodStart, e.occurredAt(), true);
					periodStart = null;
				}
				case Downtime e -> downs.add(new Instant[] { e.occurredAt(), e.occurredAt().plus(interval) });
				default -> {
				}
			}
		}
		if (periodStart != null) {
			mark(up, start, periodStart, end.plusSeconds(1), true);
		}
		for (Instant[] d : downs) {
			mark(up, start, d[0], d[1], false);
		}
		List<Point> points = new ArrayList<>((int) seconds);
		for (int i = 0; i < seconds; i++) {
			points.add(new Point(start.plusSeconds(i), !up[i]));
		}
		return points;
	}

	/** Sets every second that {@code [from, to)} touches (whole seconds for up, any overlap for down). */
	private static void mark(boolean[] up, Instant rangeStart, Instant from, Instant to, boolean value) {
		if (from == null || !to.isAfter(from)) {
			return;
		}
		long first = value ? ceilSeconds(rangeStart, from) : floorSeconds(rangeStart, from);
		long last = value ? floorSeconds(rangeStart, to) : ceilSeconds(rangeStart, to);
		for (long i = Math.max(0, first); i < Math.min(up.length, last); i++) {
			up[(int) i] = value;
		}
	}

	private static long floorSeconds(Instant base, Instant t) {
		return Math.floorDiv(Duration.between(base, t).toMillis(), 1000);
	}

	private static long ceilSeconds(Instant base, Instant t) {
		return -Math.floorDiv(-Duration.between(base, t).toMillis(), 1000);
	}

}
