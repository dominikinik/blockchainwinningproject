package com.example.uptime.checking;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.uptime.checking.application.ExecuteCheck;
import com.example.uptime.checking.domain.CheckOutcome;
import com.example.uptime.checking.domain.CheckResult;
import com.example.uptime.checking.domain.ProbeResult;

import static org.junit.jupiter.api.Assertions.*;

class ExecuteCheckTest {
	private static final Instant START = Instant.parse("2026-10-03T12:00:00Z");

	@Test
	void successIsReturnedAndDeliveredWithUniqueIdAndClockTimestamps() {
		List<CheckResult> delivered = new ArrayList<>();
		Instant end = START.plusSeconds(2);
		ExecuteCheck executor = new ExecuteCheck(ProbeResult::success, delivered::add,
				new SequenceClock(START, end, START, end));
		CheckResult result = executor.execute();
		assertEquals(CheckOutcome.SUCCESS, result.outcome());
		assertNull(result.failure());
		assertEquals(START, result.startedAt());
		assertEquals(end, result.observedAt());
		assertTrue(result.durationNanos() >= 0);
		assertSame(result, delivered.getFirst());
		assertNotEquals(result.checkId(), executor.execute().checkId());
	}

	@Test
	void probeFailureIsPreserved() {
		ProbeResult failure = ProbeResult.failure("UNAVAILABLE", "Not available");
		CheckResult result = new ExecuteCheck(() -> failure, ignored -> {},
				Clock.fixed(START, ZoneOffset.UTC)).execute();
		assertEquals(CheckOutcome.FAILURE, result.outcome());
		assertSame(failure.failure(), result.failure());
	}

	@Test
	void probeExceptionIsSanitizedAndDelivered() {
		List<CheckResult> delivered = new ArrayList<>();
		CheckResult result = new ExecuteCheck(() -> {
			throw new IllegalStateException("password=secret", new RuntimeException("token=secret"));
		}, delivered::add, Clock.fixed(START, ZoneOffset.UTC)).execute();
		assertEquals(CheckOutcome.FAILURE, result.outcome());
		assertEquals("PROBE_EXCEPTION", result.failure().code());
		assertEquals(com.example.uptime.checking.domain.FailureType.CHECK_FAILURE, result.failure().type());
		assertEquals("", result.failure().reason());
		assertEquals("Health probe failed unexpectedly", result.failure().message());
		assertTrue(result.failure().message().length() <= 512);
		assertSame(result, delivered.getFirst());
	}

	@Test
	void backwardsClockIsNormalized() {
		CheckResult result = new ExecuteCheck(ProbeResult::success, ignored -> {},
				new SequenceClock(START, START.minusSeconds(1))).execute();
		assertEquals(START, result.observedAt());
		assertTrue(result.durationNanos() >= 0);
	}

	@Test
	void sinkExceptionsPropagateForBothProbeOutcomes() {
		RuntimeException error = new IllegalStateException("aggregation failed");
		for (boolean probeThrows : List.of(false, true)) {
			ExecuteCheck executor = new ExecuteCheck(() -> {
				if (probeThrows) {
					throw new IllegalArgumentException("probe failed");
				}
				return ProbeResult.success();
			}, ignored -> { throw error; }, Clock.fixed(START, ZoneOffset.UTC));
			assertSame(error, assertThrows(IllegalStateException.class, executor::execute));
		}
	}

	private static final class SequenceClock extends Clock {
		private final Instant[] instants;
		private int index;

		private SequenceClock(Instant... instants) {
			this.instants = instants;
		}

		@Override
		public ZoneId getZone() { return ZoneOffset.UTC; }

		@Override
		public Clock withZone(ZoneId zone) {
			return zone.equals(getZone()) ? this : Clock.fixed(instants[index], zone);
		}

		@Override
		public Instant instant() { return instants[index++]; }
	}
}
