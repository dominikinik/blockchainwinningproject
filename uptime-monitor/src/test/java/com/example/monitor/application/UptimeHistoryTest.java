package com.example.monitor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.monitor.application.UptimeHistory.Point;
import com.example.monitor.support.MutableClock;

class UptimeHistoryTest {

	private static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

	private final MutableClock clock = new MutableClock(T0);

	private final UptimeHistory history = new UptimeHistory(clock, Duration.ofSeconds(2), 5, 60);

	@Test
	void everySecondIsDownBeforeTheFirstResult() {
		assertThat(history.range(T0, T0.plusSeconds(3))).hasSize(4).allMatch(Point::down);
	}

	@Test
	void upFromTheFirstResultAndDownForTheIntervalOfEachDownResult() {
		history.record(T0.plusSeconds(2), true);
		history.record(T0.plusSeconds(4), false);
		history.record(T0.plusSeconds(6), true);

		assertThat(downs(history.range(T0, T0.plusSeconds(9))))
			.containsExactly(true, true, false, false, true, true, false, false, false, false);
	}

	@Test
	void aDownResultOffTheSecondMarksEverySecondItTouches() {
		history.record(T0, true);
		history.record(T0.plusMillis(1_500), false);

		assertThat(downs(history.range(T0, T0.plusSeconds(4)))).containsExactly(false, true, true, true, false);
	}

	@Test
	void defaultsToTheLastSecondsEndingNow() {
		clock.set(T0.plusMillis(10_700));
		List<Point> points = history.range(null, null);

		assertThat(points).extracting(Point::time)
			.containsExactly(T0.plusSeconds(6), T0.plusSeconds(7), T0.plusSeconds(8), T0.plusSeconds(9),
					T0.plusSeconds(10));
	}

	@Test
	void rejectsReversedOrTooLongRanges() {
		assertThatIllegalArgumentException().isThrownBy(() -> history.range(T0.plusSeconds(1), T0));
		assertThatIllegalArgumentException().isThrownBy(() -> history.range(T0, T0.plusSeconds(60)))
			.withMessageContaining("exceeds maximum");
		assertThat(history.range(T0, T0.plusSeconds(59))).hasSize(60);
	}

	@Test
	void forgetsDownResultsOlderThanTheLongestRange() {
		history.record(T0, false);
		history.record(T0.plusSeconds(200), true);

		// The DOWN at T0 was dropped, so the first-result rule alone decides: up from T0.
		assertThat(history.range(T0, T0.plusSeconds(1))).noneMatch(Point::down);
	}

	private static List<Boolean> downs(List<Point> points) {
		return points.stream().map(Point::down).toList();
	}

}
