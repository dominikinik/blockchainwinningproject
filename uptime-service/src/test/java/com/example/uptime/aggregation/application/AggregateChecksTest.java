package com.example.uptime.aggregation.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.uptime.aggregation.domain.EventStatus;
import com.example.uptime.aggregation.domain.TimeRange;
import com.example.uptime.aggregation.domain.UptimeEvent;
import com.example.uptime.checking.domain.CheckFailure;
import com.example.uptime.checking.domain.CheckOutcome;
import com.example.uptime.checking.domain.CheckResult;
import com.example.uptime.checking.domain.FailureType;
import com.example.uptime.tracking.domain.TrackingSession;
import com.example.uptime.tracking.domain.TrackingStatus;

import static org.junit.jupiter.api.Assertions.*;

class AggregateChecksTest {
	private static final Instant START = Instant.parse("2026-10-03T12:00:00Z");
	private final List<UptimeEvent> saved = new ArrayList<>();
	private final MutableClock clock = new MutableClock();

	// Dense regression cadence: 100 observations/minute, with a five-interval (3 s) gap.
	private AggregateChecks service(int cap) {
		return new AggregateChecks(saved::addAll, clock, 100, cap, 2, 10, 25, 3000);
	}
	private UUID start(AggregateChecks service, long ms) {
		UUID id = UUID.randomUUID();
		service.startSession(new TrackingSession(id, START.plusMillis((ms) * 60), null, TrackingStatus.ACTIVE, null));
		return id;
	}
	private CheckResult check(long ms, String code, FailureType type, String reason, String message) {
		Instant at = START.plusMillis((ms) * 60);
		return new CheckResult(UUID.randomUUID(), at, at, 0,
				code == null ? CheckOutcome.SUCCESS : CheckOutcome.FAILURE,
				code == null ? null : new CheckFailure(code, message, type, reason));
	}
	private void good(AggregateChecks service, long ms) {
		service.accept(check(ms, null, null, null, null));
	}
	private void bad(AggregateChecks service, long ms, String code) {
		service.accept(check(ms, code, FailureType.DOWNTIME, "reason", "diagnostic"));
	}

	@Test
	void twentyGoodTwentyBadMergeDespiteMessagesAndStopDoesNotFollowFailure() {
		var service = service(10);
		assertThrows(CheckAdmissionException.class, () -> good(service, 0));
		start(service, 0);
		for (int i = 0; i < 20; i++) good(service, i * 10);
		for (int i = 20; i < 40; i++) service.accept(check(i * 10, "DOWN", FailureType.DOWNTIME, "same", "message " + i));
		assertTrue(service.hasActiveSession());
		service.stopSession(START.plusMillis((400) * 60));
		service.persistPending();
		var event = saved.getFirst();
		assertEquals(40, event.totalChecks());
		assertEquals(20, event.successfulChecks());
		assertEquals(EventStatus.FAILED, event.status());
		assertTrue(event.partialCoverage());
		var bad = event.badEvents().getFirst();
		assertEquals(1, event.badEvents().size());
		assertEquals(20, bad.observationCount());
		assertEquals(START.plusMillis((200) * 60), bad.windowStart());
		assertEquals(START.plusMillis((400) * 60), bad.windowEnd());
	}

	@Test
	void differentCodesReasonsTypesSuccessAndGapSplitRuns() {
		var service = service(10); start(service, 0);
		bad(service, 0, "A"); bad(service, 10, "B");
		service.accept(check(20, "B", FailureType.CHECK_FAILURE, "reason", "message"));
		service.accept(check(30, "B", FailureType.CHECK_FAILURE, "other", "message"));
		good(service, 40); bad(service, 50, "A"); bad(service, 200, "A");
		service.stopSession(START.plusMillis((210) * 60)); service.persistPending();
		assertEquals(6, saved.getFirst().badEvents().size());
		assertEquals(List.of(new TimeRange(START.plusMillis((100) * 60), START.plusMillis((200) * 60))), saved.getFirst().unknownIntervals());
	}

	@Test
	void boundaryCarryHasOriginalTimesAndZeroCountAndExpires() {
		var service = service(10); start(service, 990);
		bad(service, 990, "A");
		service.completeBefore(START.plusSeconds((1) * 60));
		service.stopSession(START.plusMillis((1100) * 60)); service.persistPending();
		var first = saved.get(0).badEvents().getFirst();
		var carry = saved.get(1).badEvents().getFirst();
		assertNotEquals(first.id(), carry.id());
		assertEquals(0, carry.observationCount());
		assertEquals(START.plusMillis((990) * 60), carry.firstObservedAt());
		assertEquals(START.plusMillis((990) * 60), carry.lastObservedAt());
		assertEquals(START.plusMillis((1040) * 60), carry.windowEnd());
		assertEquals(List.of(new TimeRange(START.plusMillis((1040) * 60), START.plusMillis((1100) * 60))), saved.get(1).unknownIntervals());
	}

	@Test
	void partialSameSecondSessionsHaveDifferentParentsAndNoObservationIsUnknown() {
		var service = service(10);
		UUID first = start(service, 100);
		service.stopSession(START.plusMillis((200) * 60));
		UUID second = start(service, 300);
		good(service, 300); service.stopSession(START.plusMillis((320) * 60)); service.persistPending();
		assertEquals(first, saved.get(0).sessionId());
		assertEquals(second, saved.get(1).sessionId());
		assertNotEquals(saved.get(0).id(), saved.get(1).id());
		assertEquals(EventStatus.UNKNOWN, saved.get(0).status());
		assertEquals(EventStatus.SUCCESS, saved.get(1).status());
		assertTrue(saved.get(1).partialCoverage());
	}

	@Test
	void nominalCountDoesNotHideGapAndDuplicateDoesNotModifyCarry() {
		var service = service(10); start(service, 0);
		var original = check(0, "A", FailureType.DOWNTIME, "reason", "message");
		service.accept(original);
		for (int i = 0; i < 100; i++) good(service, 500 + i);
		service.accept(original);
		service.completeBefore(START.plusSeconds((1) * 60)); service.persistPending();
		assertEquals(101, saved.getFirst().totalChecks());
		assertTrue(saved.getFirst().partialCoverage());
		assertFalse(saved.getFirst().unknownIntervals().isEmpty());
		assertEquals(START.plusMillis((50) * 60), saved.getFirst().badEvents().getFirst().windowEnd());
		assertThrows(CheckAdmissionException.class, () -> good(service, 999));
		assertEquals(1, service.status().lateChecks());
	}

	@Test
	void outOfOrderAdmissionIsRejectedButFarForwardFinalizationIsBoundedAndResumable() {
		var service = service(2);
		start(service, 0);
		good(service, 20);
		assertThrows(CheckAdmissionException.class, () -> good(service, 10));
		assertEquals(1, service.status().outOfOrderChecks());

		Instant farAhead = START.plusSeconds((1_000_000) * 60);
		service.completeBefore(farAhead);
		assertEquals(0, service.status().openWindows());
		assertEquals(2, service.status().pendingEvents());
		assertEquals(0, service.status().capacityRejections());
		service.completeBefore(farAhead);
		assertEquals(2, service.status().pendingEvents());
		assertTrue(service.hasActiveSession());
		assertThrows(CheckAdmissionException.class, () -> good(service, 2000));

		service.persistPending();
		service.completeBefore(farAhead);
		service.persistPending();
		assertEquals(List.of(START, START.plusSeconds((1) * 60), START.plusSeconds((2) * 60), START.plusSeconds((3) * 60)),
				saved.stream().map(UptimeEvent::windowStart).toList());
		assertTrue(service.hasActiveSession());
	}

	@Test
	void fullOpenBufferBecomesPersistableAndBacklogDrainsAfterStoreRecovery() {
		var unavailable = new AtomicBoolean(true);
		var service = new AggregateChecks(events -> {
			if (unavailable.get()) {
				throw new IllegalStateException("Store unavailable");
			}
			saved.addAll(events);
		}, clock, 100, 2, 2, 10, 25, 3000);
		start(service, 0);
		bad(service, 0, "A");
		good(service, 1000);
		assertEquals(2, service.status().openWindows());

		service.completeBefore(START.plusSeconds((5) * 60));
		assertEquals(0, service.status().openWindows());
		assertEquals(2, service.status().pendingEvents());
		assertThrows(IllegalStateException.class, service::persistPending);
		service.completeBefore(START.plusSeconds((5) * 60));
		assertEquals(2, service.status().pendingEvents());
		assertTrue(service.hasActiveSession());

		unavailable.set(false);
		clock.now = START.plusMillis((10) * 60);
		service.persistPending();
		for (int i = 0; i < 2; i++) {
			service.completeBefore(START.plusSeconds((5) * 60));
			service.persistPending();
		}
		assertEquals(5, saved.size());
		for (int i = 0; i < 5; i++) {
			assertEquals(START.plusSeconds((i) * 60), saved.get(i).windowStart());
			assertEquals(START.plusSeconds((i + 1) * 60), saved.get(i).windowEnd());
		}
		assertEquals(EventStatus.UNKNOWN, saved.get(4).status());
		assertEquals(0, service.status().pendingEvents());
		assertTrue(service.hasActiveSession());
	}

	@Test
	void stopAtCapacityCapturesCutoffAndEventuallyPersistsExactPartialTail() {
		var unavailable = new AtomicBoolean(true);
		var service = new AggregateChecks(events -> {
			if (unavailable.get()) {
				throw new IllegalStateException("Store unavailable");
			}
			saved.addAll(events);
		}, clock, 100, 2, 2, 10, 25, 3000);
		start(service, 0);
		bad(service, 0, "A");
		good(service, 1000);
		service.completeBefore(START.plusSeconds((2) * 60));
		Instant stop = START.plusMillis((4500) * 60);
		assertThrows(IllegalStateException.class, service::persistPending);

		assertDoesNotThrow(() -> service.stopSession(stop));
		assertFalse(service.hasActiveSession());
		assertFalse(service.status().activeSession());
		assertTrue(service.hasUnfinalizedSession());
		assertThrows(CheckAdmissionException.class, () -> good(service, 2000));
		assertThrows(IllegalStateException.class, () -> start(service, 5000));
		assertDoesNotThrow(() -> service.stopSession(stop));
		assertThrows(IllegalArgumentException.class, () -> service.stopSession(stop.plusNanos(1)));
		service.completeBefore(START.plusSeconds((100) * 60));
		assertEquals(2, service.status().pendingEvents());
		assertTrue(service.hasUnfinalizedSession());

		unavailable.set(false);
		clock.now = START.plusMillis((10) * 60);
		service.persistPending();
		service.completeBefore(START.plusSeconds((100) * 60));
		assertEquals(2, service.status().pendingEvents());
		assertTrue(service.hasUnfinalizedSession());
		service.persistPending();
		service.completeBefore(START.plusSeconds((100) * 60));
		assertFalse(service.hasUnfinalizedSession());
		assertEquals(1, service.status().pendingEvents());
		service.persistPending();
		assertEquals(5, saved.size());
		assertEquals(START.plusSeconds((4) * 60), saved.getLast().windowStart());
		assertEquals(stop, saved.getLast().windowEnd());
		assertEquals(EventStatus.UNKNOWN, saved.getLast().status());
		assertEquals(0, service.status().openWindows());
		assertEquals(0, service.status().pendingEvents());
		assertDoesNotThrow(() -> service.stopSession(stop));
		assertDoesNotThrow(() -> start(service, 5000));
	}

	@Test
	void stopClosesExistingOpenWindowEvenWhenThereIsNoFreeCapacity() {
		var service = service(1);
		start(service, 0);
		bad(service, 0, "A");
		service.stopSession(START.plusMillis((250) * 60));
		assertFalse(service.hasActiveSession());
		assertFalse(service.hasUnfinalizedSession());
		assertEquals(0, service.status().openWindows());
		assertEquals(1, service.status().pendingEvents());
		service.persistPending();
		assertEquals(START.plusMillis((250) * 60), saved.getFirst().windowEnd());
		assertEquals(EventStatus.FAILED, saved.getFirst().status());
	}

	@Test
	void stopWithNoChecksDrainsUnknownCoverageAndNeverMovesItsCutoff() {
		var service = service(1);
		start(service, 100);
		Instant stop = START.plusMillis((2250) * 60);
		service.stopSession(stop);
		assertFalse(service.hasActiveSession());
		assertTrue(service.hasUnfinalizedSession());
		service.persistPending();
		service.completeBefore(START.plusMillis((1500) * 60));
		service.persistPending();
		service.completeBefore(START.plusSeconds((20) * 60));
		service.persistPending();
		assertFalse(service.hasUnfinalizedSession());
		assertEquals(3, saved.size());
		assertEquals(START.plusMillis((100) * 60), saved.getFirst().windowStart());
		assertEquals(stop, saved.getLast().windowEnd());
		for (UptimeEvent event : saved) {
			assertEquals(EventStatus.UNKNOWN, event.status());
			assertEquals(0, event.totalChecks());
			assertEquals(List.of(new TimeRange(event.windowStart(), event.windowEnd())), event.unknownIntervals());
		}
	}

	@Test
	void stopCannotPrecedeAdmittedOrCompletedTimeAndInvalidStopDoesNotDisableAdmission() {
		var service = service(2);
		start(service, 0);
		good(service, 100);
		assertThrows(IllegalArgumentException.class, () -> service.stopSession(START.plusMillis((99) * 60)));
		assertTrue(service.hasActiveSession());
		service.completeBefore(START.plusSeconds((1) * 60));
		assertThrows(IllegalArgumentException.class, () -> service.stopSession(START.plusMillis((999) * 60)));
		assertTrue(service.hasActiveSession());
		service.stopSession(START.plusSeconds((1) * 60));
		assertFalse(service.hasUnfinalizedSession());
	}

	@Test
	void exactSecondStopDoesNotCreateExtraParentAndWholeSecondHealthyCoverageIsSuccess() {
		var service = service(3); start(service, 0);
		for (int i = 0; i < 100; i++) good(service, i * 10);
		service.completeBefore(START.plusMillis((999) * 60));
		assertEquals(0, service.status().pendingEvents());
		service.stopSession(START.plusSeconds((1) * 60)); service.persistPending();
		assertEquals(1, saved.size());
		assertEquals(EventStatus.SUCCESS, saved.getFirst().status());
		assertFalse(saved.getFirst().partialCoverage());
		assertTrue(saved.getFirst().badEvents().isEmpty());
	}

	@Test
	void immediateEmptyStopCreatesNoCoverageAndCannotStartWhileActive() {
		var service = service(2); start(service, 100);
		assertThrows(IllegalStateException.class, () -> start(service, 100));
		service.stopSession(START.plusMillis((100) * 60)); service.persistPending();
		assertTrue(saved.isEmpty());
		assertFalse(service.hasActiveSession());
		assertFalse(service.hasUnfinalizedSession());
	}

	@Test
	void immediateFailureStopAllowsPointFragment() {
		var service = service(2); start(service, 100); bad(service, 100, "A");
		service.stopSession(START.plusMillis((100) * 60)); service.persistPending();
		var fragment = saved.getFirst().badEvents().getFirst();
		assertEquals(fragment.windowStart(), fragment.windowEnd());
		assertEquals(EventStatus.FAILED, saved.getFirst().status());
	}

	@Test
	void retriesSameBatchIdentityWithExponentialBackoff() {
		List<List<UptimeEvent>> attempts = new ArrayList<>();
		var service = new AggregateChecks(events -> { attempts.add(events); if (attempts.size() <= 3) throw new IllegalStateException(); }, clock, 100, 10, 2, 10, 25, 3000);
		start(service, 0); bad(service, 0, "A"); service.completeBefore(START.plusSeconds((3) * 60));
		long elapsed = 0;
		for (long delay : new long[] {10, 20, 25}) {
			assertThrows(IllegalStateException.class, service::persistPending);
			assertEquals(START.plusMillis(elapsed + delay), service.status().nextRetryAt());
			clock.now = START.plusMillis(elapsed + delay - 1); service.persistPending();
			elapsed += delay; clock.now = START.plusMillis(elapsed);
		}
		service.persistPending();
		for (var batch : attempts) assertSame(attempts.getFirst(), batch);
		assertEquals(1, service.status().pendingEvents());
		assertEquals(0, service.status().consecutivePersistenceFailures());
	}

	@Test
	void slowStoreIsSingleFlightAndDoesNotBlockAdmission() throws Exception {
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
		var calls = new AtomicInteger();
		var service = new AggregateChecks(events -> {
			calls.incrementAndGet(); entered.countDown();
			try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
		}, clock, 100, 10, 1, 10, 25, 3000);
		start(service, 0); good(service, 0); service.completeBefore(START.plusSeconds((1) * 60));
		try (var executor = Executors.newFixedThreadPool(2)) {
			var future = executor.submit(service::persistPending);
			try {
				assertTrue(entered.await(2, TimeUnit.SECONDS));
				executor.submit(() -> { good(service, 1000); service.completeBefore(START.plusSeconds((2) * 60)); service.persistPending(); }).get(2, TimeUnit.SECONDS);
				assertEquals(1, calls.get()); assertEquals(2, service.status().pendingEvents());
			} finally { release.countDown(); }
			future.get(2, TimeUnit.SECONDS);
		}
		assertEquals(1, service.status().pendingEvents());
	}

	@Test
	void sixTenSecondChecksCompleteOneMinuteAndBoundaryCheckBelongsToNext() {
		var service = new AggregateChecks(saved::addAll, clock, 6, 10, 10, 10, 25, 50_000);
		start(service, 0);
		for (int i = 0; i < 6; i++) service.accept(minuteCheck(i * 10_000, false));
		service.completeBefore(START.plusSeconds(59));
		assertEquals(0, service.status().pendingEvents());
		service.accept(minuteCheck(60_000, false));
		service.completeBefore(START.plusSeconds(60));
		service.persistPending();
		assertEquals(1, saved.size());
		var minute = saved.getFirst();
		assertEquals(START.plusSeconds(60), minute.windowEnd());
		assertEquals(6, minute.totalChecks());
		assertEquals(6, minute.successfulChecks());
		assertEquals(EventStatus.SUCCESS, minute.status());
		assertFalse(minute.partialCoverage());
		service.stopSession(START.plusSeconds(70));
		service.persistPending();
		assertEquals(1, saved.getLast().totalChecks());
		assertEquals(START.plusSeconds(60), saved.getLast().bucketStart());
		assertTrue(saved.getLast().partialCoverage());
	}

	@Test
	void defaultGapExpiresAfterFiveTenSecondIntervalsAndFailureRestarts() {
		var service = new AggregateChecks(saved::addAll, clock, 6, 10, 10, 10, 25);
		start(service, 0);
		service.accept(minuteCheck(0, true));
		service.accept(minuteCheck(55_000, true));
		service.stopSession(START.plusSeconds(60));
		service.persistPending();
		var minute = saved.getFirst();
		assertEquals(2, minute.badEvents().size());
		assertEquals(List.of(new TimeRange(START.plusSeconds(50), START.plusSeconds(55))), minute.unknownIntervals());
		assertEquals(EventStatus.FAILED, minute.status());
		assertTrue(minute.partialCoverage());
	}

	private CheckResult minuteCheck(long millis, boolean failed) {
		Instant at = START.plusMillis(millis);
		return new CheckResult(UUID.randomUUID(), at, at, 0,
				failed ? CheckOutcome.FAILURE : CheckOutcome.SUCCESS,
				failed ? new CheckFailure("DOWN", "Down", FailureType.DOWNTIME, "reason") : null);
	}

	private static final class MutableClock extends Clock {
		Instant now = START;
		public ZoneId getZone() { return ZoneOffset.UTC; }
		public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
		public Instant instant() { return now; }
	}
}
