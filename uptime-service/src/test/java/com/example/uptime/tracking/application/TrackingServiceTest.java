package com.example.uptime.tracking.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.example.uptime.aggregation.domain.BadEventType;
import com.example.uptime.checking.domain.FailureType;
import com.example.uptime.checking.domain.ProbeResult;
import com.example.uptime.tracking.domain.*;

class TrackingServiceTest {
	@Test
	void noChecksBeforeExplicitStartAndLifecycleRecordsPersist() {
		TrackingFixture f = new TrackingFixture();
		f.tracking.sample();
		verifyNoInteractions(f.probe);
		assertThat(f.tracking.state()).isEmpty();
		TrackingSession session = f.tracking.start();
		assertThat(f.tracking.events(session.id())).singleElement().satisfies(e -> {
			assertThat(e.type()).isEqualTo(TrackingEventType.START);
			assertThat(e.occurredAt()).isEqualTo(TrackingFixture.START);
		});
		f.tracking.sample();
		f.at((20) * 60);
		assertThat(f.tracking.stop().status()).isEqualTo(TrackingStatus.STOPPING);
		f.tracking.sample();
		verify(f.probe, times(1)).check();
		assertThat(f.tracking.events(session.id())).extracting(TrackingEvent::type)
				.containsExactly(TrackingEventType.START, TrackingEventType.STOP);
		f.tracking.flush();
		assertThat(f.tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(f.saved).singleElement().satisfies(e -> assertThat(e.windowEnd()).isEqualTo(TrackingFixture.START.plusMillis((20) * 60)));
	}

	@Test
	void downtimeAndRequestFailuresNeverStopTrackingAndDifferentErrorsSplit() {
		TrackingFixture f = new TrackingFixture();
		f.tracking.start();
		for (int i = 0; i < 2; i++) { f.at((i * 10) * 60); f.tracking.sample(); }
		when(f.probe.check()).thenReturn(ProbeResult.failure("HEALTH_DOWN", "Unhealthy", FailureType.DOWNTIME, "AWS"));
		for (int i = 2; i < 4; i++) { f.at((i * 10) * 60); f.tracking.sample(); }
		when(f.probe.check()).thenReturn(ProbeResult.failure("HTTP_STATUS", "Unavailable", FailureType.CHECK_FAILURE, "503"));
		f.at((40) * 60); f.tracking.sample();
		when(f.probe.check()).thenReturn(ProbeResult.failure("HTTP_TIMEOUT", "Timed out"));
		f.at((50) * 60); f.tracking.sample();
		assertThat(f.tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.ACTIVE);
		assertThat(f.aggregation.hasActiveSession()).isTrue();
		when(f.probe.check()).thenReturn(ProbeResult.success());
		f.at((60) * 60); f.tracking.sample();
		f.at((70) * 60); f.tracking.stop(); f.tracking.flush();
		assertThat(f.saved).singleElement().satisfies(e -> {
			assertThat(e.badEvents()).hasSize(3);
			assertThat(e.badEvents()).extracting(b -> b.type())
					.containsExactly(BadEventType.DOWNTIME, BadEventType.CHECK_FAILURE, BadEventType.CHECK_FAILURE);
			assertThat(e.badEvents().getFirst().windowStart()).isEqualTo(TrackingFixture.START.plusMillis((20) * 60));
			assertThat(e.badEvents().getFirst().windowEnd()).isEqualTo(TrackingFixture.START.plusMillis((40) * 60));
			assertThat(e.badEvents().getFirst().observationCount()).isEqualTo(2);
		});
	}

	@Test
	void stopIntentFailureKeepsExactCutoffAndRetriesSameEvent() {
		TrackingFixture f = new TrackingFixture();
		TrackingSession session = f.tracking.start();
		f.tracking.sample(); f.at((20) * 60);
		doThrow(new DataAccessResourceFailureException("secret connection details")).doCallRealMethod()
				.when(f.lifecycle).saveStop(any(), any());
		f.tracking.stop();
		TrackingEvent stop = f.tracking.events(session.id()).getLast();
		assertThat(f.tracking.lifecycleFailure()).isEqualTo("DataAccessResourceFailureException");
		f.at((30) * 60); f.tracking.sample();
		verify(f.probe, times(1)).check();
		assertThat(f.tracking.sessions(TrackingFixture.START.plusMillis((20) * 60), TrackingFixture.START.plusMillis((30) * 60))).isEmpty();
		f.tracking.flush();
		verify(f.lifecycle, times(2)).saveStop(any(), eq(stop));
		assertThat(f.tracking.events(session.id())).containsExactly(f.memory.events(session.id()).toArray(TrackingEvent[]::new));
		assertThat(f.tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(f.tracking.lifecycleFailure()).isNull();
	}

	@Test
	void ambiguousStartRetryReusesBothIdentitiesAndDoesNotSampleUntilAcknowledged() {
		TrackingFixture f = new TrackingFixture();
		AtomicInteger attempts = new AtomicInteger();
		doAnswer(call -> {
			call.callRealMethod();
			if (attempts.getAndIncrement() == 0) throw new DataAccessResourceFailureException("ambiguous commit");
			return null;
		}).when(f.lifecycle).saveStart(any(), any());
		assertThatThrownBy(f.tracking::start).isInstanceOf(DataAccessResourceFailureException.class);
		TrackingSession stored = f.memory.latest().orElseThrow();
		f.tracking.flush();
		assertThat(f.tracking.lifecycleFailure()).isEqualTo("DataAccessResourceFailureException");
		f.tracking.sample(); verifyNoInteractions(f.probe);
		assertThat(f.tracking.start().id()).isEqualTo(stored.id());
		assertThat(f.memory.events(stored.id())).hasSize(1);
		f.tracking.sample(); verify(f.probe).check();
	}

	@Test
	void explicitStopAtCapacityDrainsIncrementallyWithoutLosingCoverage() {
		TrackingFixture f = new TrackingFixture(2);
		f.tracking.start(); f.tracking.sample(); f.at((5000) * 60);
		f.tracking.stop();
		assertThat(f.aggregation.hasActiveSession()).isFalse();
		assertThat(f.aggregation.hasUnfinalizedSession()).isTrue();
		assertThatThrownBy(f.tracking::start).isInstanceOf(TrackingConflictException.class);
		for (int i = 0; i < 5 && f.tracking.state().orElseThrow().status() != TrackingStatus.STOPPED; i++) f.tracking.flush();
		assertThat(f.tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(f.saved).hasSize(5);
		assertThat(f.saved.getFirst().windowStart()).isEqualTo(TrackingFixture.START);
		assertThat(f.saved.getLast().windowEnd()).isEqualTo(TrackingFixture.START.plusSeconds((5) * 60));
		for (int i = 1; i < f.saved.size(); i++) {
			assertThat(f.saved.get(i).windowStart()).isEqualTo(f.saved.get(i - 1).windowEnd());
		}
	}

	@Test
	void sameSecondSessionsRemainDistinctAndGapIsNotTracked() {
		TrackingFixture f = new TrackingFixture();
		f.at((100) * 60); TrackingSession first = f.tracking.start(); f.tracking.sample();
		f.at((120) * 60); f.tracking.stop(); f.tracking.flush();
		f.at((140) * 60); TrackingSession second = f.tracking.start(); f.tracking.sample();
		f.at((160) * 60); f.tracking.stop(); f.tracking.flush();
		assertThat(f.saved).hasSize(2);
		assertThat(f.saved).extracting(e -> e.bucketStart()).containsOnly(TrackingFixture.START);
		assertThat(f.saved).extracting(e -> e.sessionId()).containsExactly(first.id(), second.id());
		assertThat(f.tracking.sessions(TrackingFixture.START.plusMillis((130) * 60), TrackingFixture.START.plusMillis((130) * 60))).isEmpty();
	}

	@Test
	void repeatedCommandsAreExplicitConflictsAndZeroLengthSessionHasBothEvents() {
		TrackingFixture f = new TrackingFixture();
		assertThatThrownBy(f.tracking::stop).isInstanceOf(TrackingConflictException.class);
		TrackingSession session = f.tracking.start();
		assertThatThrownBy(f.tracking::start).isInstanceOf(TrackingConflictException.class);
		f.tracking.stop();
		assertThatThrownBy(f.tracking::stop).isInstanceOf(TrackingConflictException.class);
		f.tracking.flush();
		assertThat(f.tracking.events(session.id())).extracting(TrackingEvent::type)
				.containsExactly(TrackingEventType.START, TrackingEventType.STOP);
		assertThat(f.saved).isEmpty();
		assertThat(f.tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
	}

	@Test
	void stopCannotCutBetweenObservationAndAdmission() throws Exception {
		TrackingFixture f = new TrackingFixture();
		f.tracking.start(); f.at((10) * 60);
		CountDownLatch observed = new CountDownLatch(1), release = new CountDownLatch(1), stopping = new CountDownLatch(1);
		f.beforeAdmission.set(result -> { observed.countDown(); await(release); });
		var threads = Executors.newFixedThreadPool(2);
		try {
			var sample = threads.submit(f.tracking::sample);
			await(observed); f.at((20) * 60);
			var stop = threads.submit(() -> { stopping.countDown(); return f.tracking.stop(); });
			await(stopping);
			assertThatThrownBy(() -> stop.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			release.countDown(); sample.get(5, TimeUnit.SECONDS); stop.get(5, TimeUnit.SECONDS);
			f.tracking.flush();
			assertThat(f.saved.getFirst().totalChecks()).isEqualTo(1);
			assertThat(f.aggregation.status().rejectedChecks()).isZero();
		} finally { release.countDown(); threads.shutdownNow(); assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
	}

	@Test
	void slowPersistenceDoesNotBlockSampling() throws Exception {
		TrackingFixture f = new TrackingFixture();
		f.tracking.start(); f.tracking.sample(); f.at((1000) * 60);
		CountDownLatch saving = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(call -> { saving.countDown(); await(release); f.commit(call.getArgument(0)); return null; })
				.when(f.store).saveAll(anyList());
		var threads = Executors.newFixedThreadPool(2);
		try {
			var flush = threads.submit(f.tracking::flush); await(saving);
			threads.submit(f.tracking::sample).get(5, TimeUnit.SECONDS);
			assertThat(flush.isDone()).isFalse();
			assertThat(f.aggregation.status().openWindows()).isEqualTo(1);
			release.countDown(); flush.get(5, TimeUnit.SECONDS);
			assertThat(f.aggregation.status().rejectedChecks()).isZero();
		} finally { release.countDown(); threads.shutdownNow(); assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
	}

	private static void await(CountDownLatch latch) {
		try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
