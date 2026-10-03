package com.example.uptime.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.uptime.state.ApplicationStateHealthIndicator;
import com.example.uptime.state.ApplicationStateService;
import com.example.uptime.support.MutableClock;
import com.example.uptime.uptime.UptimeRecord;
import com.example.uptime.uptime.UptimeRecordRepository;

class UptimeSamplerTest {

	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private final ApplicationStateService state = new ApplicationStateService();

	private final UptimeRecordRepository repository = mock(UptimeRecordRepository.class);

	private final MutableClock clock = new MutableClock(T0);

	private final UptimeSampler sampler = new UptimeSampler(new ApplicationStateHealthIndicator(state), repository,
			clock);

	@Test
	void flushWithoutSamplesWritesNothing() {
		sampler.flush();
		verify(repository, never()).saveAll(anyList());
	}

	@Test
	void currentSecondIsNotFlushedWhileStillFilling() {
		sampleAt(T0.plusMillis(100));
		clock.set(T0.plusMillis(900));
		sampler.flush();
		verify(repository, never()).saveAll(anyList());
	}

	@Test
	void completedUpSecondIsPersistedWithCounts() {
		sampleAt(T0.plusMillis(10));
		sampleAt(T0.plusMillis(20));
		sampleAt(T0.plusMillis(990));

		List<UptimeRecord> saved = flushAt(T0.plusSeconds(1));

		assertThat(saved).singleElement().satisfies(r -> {
			assertThat(r.getTimestamp()).isEqualTo(T0);
			assertThat(r.isUp()).isTrue();
			assertThat(r.getSamples()).isEqualTo(3);
			assertThat(r.getUpSamples()).isEqualTo(3);
		});
	}

	@Test
	void secondWithAnyDownSampleIsDown() {
		sampleAt(T0.plusMillis(10));
		state.stop();
		sampleAt(T0.plusMillis(20));
		state.start();
		sampleAt(T0.plusMillis(30));

		assertThat(flushAt(T0.plusSeconds(1))).singleElement().satisfies(r -> {
			assertThat(r.isUp()).isFalse();
			assertThat(r.getSamples()).isEqualTo(3);
			assertThat(r.getUpSamples()).isEqualTo(2);
		});
	}

	@Test
	void fullyDownSecondHasNoUpSamples() {
		state.stop();
		sampleAt(T0.plusMillis(10));
		sampleAt(T0.plusMillis(20));

		assertThat(flushAt(T0.plusSeconds(1))).singleElement().satisfies(r -> {
			assertThat(r.isUp()).isFalse();
			assertThat(r.getUpSamples()).isZero();
		});
	}

	@Test
	void severalCompletedSecondsAreFlushedInOrderAndCurrentOneKept() {
		sampleAt(T0.plusSeconds(2));
		sampleAt(T0.plusMillis(500));
		sampleAt(T0.plusSeconds(1));
		sampleAt(T0.plusSeconds(3));

		List<UptimeRecord> saved = flushAt(T0.plusSeconds(3).plusMillis(1));

		assertThat(saved).extracting(UptimeRecord::getTimestamp)
			.containsExactly(T0, T0.plusSeconds(1), T0.plusSeconds(2));
		assertThat(flushAt(T0.plusSeconds(4))).extracting(UptimeRecord::getTimestamp)
			.containsExactly(T0.plusSeconds(3));
	}

	@Test
	void flushedSecondsAreNotWrittenTwice() {
		sampleAt(T0);
		flushAt(T0.plusSeconds(1));
		sampler.flush();
		verify(repository, times(1)).saveAll(anyList());
	}

	private void sampleAt(Instant time) {
		clock.set(time);
		sampler.sample();
	}

	@SuppressWarnings("unchecked")
	private List<UptimeRecord> flushAt(Instant time) {
		clock.set(time);
		ArgumentCaptor<List<UptimeRecord>> captor = ArgumentCaptor.forClass(List.class);
		clearInvocations(repository);
		sampler.flush();
		verify(repository).saveAll(captor.capture());
		return captor.getValue();
	}

}
