package com.example.monitor.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The provider's uptime per second, kept in memory from the health results the proxy relayed since it started. A
 * second is down before the first result, and when it falls in the check interval that starts at a DOWN result
 * ({@code [t, t + interval)}). Results older than {@code maxRangeSeconds} are dropped, so memory stays bounded and a
 * restart starts the history over.
 */
public class UptimeHistory {

	/** @param down whether the second counts as down */
	public record Point(Instant time, boolean down) {
	}

	private final Clock clock;

	private final Duration interval;

	private final long defaultRangeSeconds;

	private final long maxRangeSeconds;

	private Instant firstResult;

	private final Deque<Instant> downs = new ArrayDeque<>();

	/**
	 * @param interval            how long one health result stands for: the check interval
	 * @param defaultRangeSeconds length of the range when {@code from} is omitted
	 * @param maxRangeSeconds     longest range served, and how long results are kept
	 */
	public UptimeHistory(Clock clock, Duration interval, long defaultRangeSeconds, long maxRangeSeconds) {
		this.clock = clock;
		this.interval = interval;
		this.defaultRangeSeconds = defaultRangeSeconds;
		this.maxRangeSeconds = maxRangeSeconds;
	}

	/** Adds one health result observed at {@code at}. */
	public synchronized void record(Instant at, boolean up) {
		if (firstResult == null) {
			firstResult = at;
		}
		if (!up) {
			downs.addLast(at);
		}
		Instant oldest = at.minusSeconds(maxRangeSeconds).minus(interval);
		while (!downs.isEmpty() && downs.peekFirst().isBefore(oldest)) {
			downs.removeFirst();
		}
	}

	/**
	 * One point per second in {@code [from, to]}, both inclusive and truncated to seconds. {@code to} defaults to
	 * now and {@code from} to {@code defaultRangeSeconds} before it.
	 *
	 * @throws IllegalArgumentException if {@code from} is after {@code to} or the range is too long
	 */
	public synchronized List<Point> range(Instant from, Instant to) {
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
		mark(up, start, firstResult, end.plusSeconds(1), true);
		for (Instant down : downs) {
			mark(up, start, down, down.plus(interval), false);
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
