package com.example.uptime.uptime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.uptime.UptimeProperties;
import com.example.uptime.support.MutableClock;

class UptimeQueryServiceTest {

	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private final UptimeRecordRepository repository = mock(UptimeRecordRepository.class);

	private final MutableClock clock = new MutableClock(T0.plusMillis(750));

	private final UptimeQueryService service = new UptimeQueryService(repository,
			new UptimeProperties(10, 1000, 60, 5), clock);

	@BeforeEach
	void noRecordsByDefault() {
		when(repository.findById(any())).thenReturn(Optional.empty());
		when(repository.findByTimestampBetweenOrderByTimestamp(any(), any())).thenReturn(List.of());
	}

	@Test
	void atTruncatesToSecondAndReportsMissingDataAsDown() {
		assertThat(service.at(T0.plusMillis(999))).isEqualTo(new UptimePoint(T0, true));
		verify(repository).findById(T0);
	}

	@Test
	void atReflectsRecordedState() {
		when(repository.findById(T0)).thenReturn(Optional.of(new UptimeRecord(T0, true, 100, 100)));
		when(repository.findById(T0.plusSeconds(1)))
			.thenReturn(Optional.of(new UptimeRecord(T0.plusSeconds(1), false, 100, 99)));

		assertThat(service.at(T0).down()).isFalse();
		assertThat(service.at(T0.plusSeconds(1)).down()).isTrue();
	}

	@Test
	void rangeReturnsOnePointPerSecondInclusiveWithGapsAsDown() {
		when(repository.findByTimestampBetweenOrderByTimestamp(T0, T0.plusSeconds(3))).thenReturn(List.of(
				new UptimeRecord(T0.plusSeconds(1), true, 100, 100), new UptimeRecord(T0.plusSeconds(2), false, 100, 0)));

		assertThat(service.range(T0, T0.plusSeconds(3))).containsExactly(new UptimePoint(T0, true),
				new UptimePoint(T0.plusSeconds(1), false), new UptimePoint(T0.plusSeconds(2), true),
				new UptimePoint(T0.plusSeconds(3), true));
	}

	@Test
	void rangeTruncatesSubSecondBounds() {
		List<UptimePoint> points = service.range(T0.plusMillis(900), T0.plusSeconds(1).plusMillis(100));
		assertThat(points).extracting(UptimePoint::time).containsExactly(T0, T0.plusSeconds(1));
		verify(repository).findByTimestampBetweenOrderByTimestamp(T0, T0.plusSeconds(1));
	}

	@Test
	void singleSecondRange() {
		assertThat(service.range(T0, T0)).containsExactly(new UptimePoint(T0, true));
	}

	@Test
	void defaultRangeIsLastDefaultSecondsEndingNow() {
		clock.set(T0.plusSeconds(10).plusMillis(400));
		assertThat(service.range(null, null)).extracting(UptimePoint::time)
			.containsExactly(T0.plusSeconds(6), T0.plusSeconds(7), T0.plusSeconds(8), T0.plusSeconds(9),
					T0.plusSeconds(10));
	}

	@Test
	void onlyToGivenStartsDefaultRangeBeforeIt() {
		assertThat(service.range(null, T0.plusSeconds(100))).extracting(UptimePoint::time)
			.first()
			.isEqualTo(T0.plusSeconds(96));
	}

	@Test
	void onlyFromGivenEndsNow() {
		clock.set(T0.plusSeconds(20));
		assertThat(service.range(T0.plusSeconds(18), null)).extracting(UptimePoint::time)
			.containsExactly(T0.plusSeconds(18), T0.plusSeconds(19), T0.plusSeconds(20));
	}

	@Test
	void fromAfterToIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> service.range(T0.plusSeconds(1), T0))
			.withMessage("'from' must not be after 'to'");
	}

	@Test
	void rangeAtMaximumIsAllowed() {
		assertThat(service.range(T0, T0.plusSeconds(59))).hasSize(60);
	}

	@Test
	void rangeOverMaximumIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> service.range(T0, T0.plusSeconds(60)))
			.withMessage("Range of 61s exceeds maximum of 60s");
	}

}
