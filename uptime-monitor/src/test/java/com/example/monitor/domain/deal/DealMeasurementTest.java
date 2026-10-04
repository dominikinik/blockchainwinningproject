package com.example.monitor.domain.deal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;

class DealMeasurementTest {

	static final ServiceId S = ServiceId.newId();

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

	static final Duration TWO = Duration.ofSeconds(2);

	static TrackingEvent started(long at) {
		return new TrackingStarted(S, "http://p/health", TWO, T0.plusSeconds(at));
	}

	static TrackingEvent down(long at) {
		return new Downtime(S, 404, "HTTP 404", T0.plusSeconds(at));
	}

	static TrackingEvent finished(long at) {
		return new TrackingFinished(S, T0.plusSeconds(at));
	}

	@Test
	void fullyTrackedWindowWithoutFailuresIsAllUp() {
		DealMeasurement m = DealMeasurement.of(List.of(started(-100)), T0, 10, T0.plusSeconds(12));
		assertThat(m).isEqualTo(new DealMeasurement(10, 10, 0, 10, 0));
		assertThat(m.bestCaseUp()).isEqualTo(10);
	}

	@Test
	void midWindowCountsElapsedAndRemainingSeconds() {
		DealMeasurement m = DealMeasurement.of(List.of(started(-5), down(1)), T0, 10, T0.plusSeconds(4));
		assertThat(m.covered()).isEqualTo(4);
		assertThat(m.down()).isEqualTo(2);
		assertThat(m.upSoFar()).isEqualTo(2);
		assertThat(m.remaining()).isEqualTo(6);
		assertThat(m.downAhead()).isZero();
		assertThat(m.bestCaseUp()).isEqualTo(8);
	}

	@Test
	void aDowntimeAtTheMeasurementTimeCountsAsDownAhead() {
		DealMeasurement m = DealMeasurement.of(List.of(started(-5), down(4)), T0, 10, T0.plusSeconds(4));
		assertThat(m.down()).isZero();
		assertThat(m.upSoFar()).isEqualTo(4);
		assertThat(m.downAhead()).isEqualTo(2);
		assertThat(m.bestCaseUp()).isEqualTo(8);

		DealMeasurement straddling = DealMeasurement.of(List.of(started(-5), down(3)), T0, 10, T0.plusSeconds(4));
		assertThat(straddling.down()).isEqualTo(1);
		assertThat(straddling.downAhead()).isEqualTo(1);
		assertThat(straddling.bestCaseUp()).isEqualTo(8);
	}

	@Test
	void overlappingDowntimeIntervalsCountOnce() {
		DealMeasurement m = DealMeasurement.of(List.of(started(-5), down(2), new Downtime(S, 404, "",
				T0.plusMillis(3500))), T0, 10, T0.plusSeconds(10));
		assertThat(m.down()).isEqualTo(4);
	}

	@Test
	void eventsAfterNowAreIgnored() {
		DealMeasurement m = DealMeasurement.of(List.of(started(-5), down(6)), T0, 10, T0.plusSeconds(4));
		assertThat(m.downAhead()).isZero();
		assertThat(m.bestCaseUp()).isEqualTo(10);
	}

	@Test
	void eachDowntimeIsOneCheckIntervalCutAtTheWindowEnd() {
		DealMeasurement m = DealMeasurement.of(List.of(started(-5), down(2), down(4), down(9)), T0, 10,
				T0.plusSeconds(20));
		assertThat(m.down()).isEqualTo(5);
		assertThat(m.upSoFar()).isEqualTo(5);
	}

	@Test
	void downtimeOutsideTheWindowAndInternalErrorsDoNotCount() {
		DealMeasurement m = DealMeasurement.of(List.of(started(-10), down(-4),
				new InternalErrorHappened(S, 500, "HTTP 500", T0.plusSeconds(3)), down(10), down(15)), T0, 10,
				T0.plusSeconds(20));
		assertThat(m.down()).isEqualTo(0);
		assertThat(m.upSoFar()).isEqualTo(10);
	}

	@Test
	void untrackedTimeIsNeverUp() {
		List<TrackingEvent> gaps = List.of(started(-3), finished(2), started(6));
		DealMeasurement m = DealMeasurement.of(gaps, T0, 10, T0.plusSeconds(10));
		assertThat(m.covered()).isEqualTo(6);
		assertThat(m.upSoFar()).isEqualTo(6);

		DealMeasurement late = DealMeasurement.of(List.of(started(4)), T0, 10, T0.plusSeconds(10));
		assertThat(late.covered()).isEqualTo(6);
	}

	@Test
	void finishedTrackingLeavesNoRemainingCoverageButKeepsRemainingWindow() {
		DealMeasurement m = DealMeasurement.of(List.of(started(-3), finished(4)), T0, 10, T0.plusSeconds(4));
		assertThat(m.covered()).isEqualTo(4);
		assertThat(m.remaining()).isEqualTo(6);
	}

	@Test
	void beforeTheWindowNothingHasElapsed() {
		DealMeasurement m = DealMeasurement.of(List.of(started(-10)), T0, 10, T0.minusSeconds(3));
		assertThat(m).isEqualTo(new DealMeasurement(10, 0, 0, 0, 10));
	}

	@Test
	void downtimeNeverExceedsCoveredSeconds() {
		DealMeasurement m = DealMeasurement.of(List.of(started(0), down(0), down(1)), T0, 10, T0.plusSeconds(1));
		assertThat(m.covered()).isEqualTo(1);
		assertThat(m.down()).isEqualTo(1);
		assertThat(m.upSoFar()).isEqualTo(0);
	}

	@Test
	void noHistoryMeansNothingCovered() {
		DealMeasurement m = DealMeasurement.of(List.of(), T0, 10, T0.plusSeconds(10));
		assertThat(m).isEqualTo(new DealMeasurement(10, 0, 0, 0, 0));
	}

}
