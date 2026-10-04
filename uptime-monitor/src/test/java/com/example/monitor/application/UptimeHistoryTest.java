package com.example.monitor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.monitor.application.UptimeHistory.Point;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;
import com.example.monitor.support.InMemoryTrackingEventStore;
import com.example.monitor.support.MutableClock;

class UptimeHistoryTest {

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

	final ServiceId s = ServiceId.newId();

	final InMemoryTrackingEventStore events = new InMemoryTrackingEventStore();

	final MutableClock clock = new MutableClock(T0.plusSeconds(9).plusMillis(500));

	final UptimeHistory history = new UptimeHistory(events, clock, 5, 60);

	@Test
	void trackedSecondsAreUpAndDowntimeMarksOneIntervalFromItsTime() {
		append(new TrackingStarted(s, "http://p", Duration.ofSeconds(2), T0.minusSeconds(10)),
				new Downtime(s, 404, "", T0.plusSeconds(3)),
				new InternalErrorHappened(s, 500, "", T0.plusSeconds(6)));

		assertThat(downs(history.range(s, T0, T0.plusSeconds(7)))).containsExactly(false, false, false, true, true,
				false, false, false);
	}

	@Test
	void untrackedSecondsAreDown() {
		append(new TrackingStarted(s, "http://p", Duration.ofSeconds(2), T0.plusMillis(2500)),
				new TrackingFinished(s, T0.plusSeconds(5)));

		assertThat(downs(history.range(s, T0, T0.plusSeconds(6)))).containsExactly(true, true, true, false, false,
				true, true);
	}

	@Test
	void aServiceWithoutEventsIsAllDown() {
		assertThat(downs(history.range(ServiceId.newId(), T0, T0.plusSeconds(2)))).containsExactly(true, true, true);
	}

	@Test
	void defaultsToTheLastSecondsUntilNowTruncated() {
		append(new TrackingStarted(s, "http://p", Duration.ofSeconds(2), T0.minusSeconds(10)));
		List<Point> points = history.range(s, null, null);
		assertThat(points).hasSize(5);
		assertThat(points.getFirst().time()).isEqualTo(T0.plusSeconds(5));
		assertThat(points.getLast().time()).isEqualTo(T0.plusSeconds(9));
		assertThat(downs(points)).containsOnly(false);
	}

	@Test
	void invalidRangesAreRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> history.range(s, T0.plusSeconds(1), T0))
			.withMessageContaining("must not be after");
		assertThatIllegalArgumentException().isThrownBy(() -> history.range(s, T0, T0.plusSeconds(60)))
			.withMessageContaining("exceeds maximum of 60s");
		assertThat(history.range(s, T0, T0.plusSeconds(59))).hasSize(60);
	}

	private void append(TrackingEvent... list) {
		events.append(s, 0, List.of(list));
	}

	private static List<Boolean> downs(List<Point> points) {
		return points.stream().map(Point::down).toList();
	}

}
