package com.example.uptime.aggregation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.example.uptime.checking.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UptimeWindowTest {
    private static final Instant START = Instant.parse("2026-10-03T12:00:00Z");

    @Test void fragmentIdentityIsStableAndFinalizedCollectionsAreImmutable() {
        UUID session = UUID.randomUUID(); UUID run = UUID.randomUUID();
        var window = new UptimeWindow(session, START, START, 100);
        var key = new FailureKey(BadEventType.DOWNTIME, "DOWN", "reason");
        var result = new CheckResult(UUID.randomUUID(), START, START, 0, CheckOutcome.FAILURE,
                new CheckFailure("DOWN", "message", FailureType.DOWNTIME, "reason"));
        window.observe(result, run, key, START);
        window.observe(result, run, key, START);
        window.cover(START, START.plusMillis((50) * 60), run, key, START, START, "message", true);
        window.cover(START.plusMillis((50) * 60), START.plusSeconds((1) * 60), null, null, null, null, null, false);
        var event = window.close(START.plusSeconds((1) * 60));
        assertSame(event, window.close(START.plusSeconds((1) * 60)));
        assertEquals(1, event.failedChecks());
        assertEquals(1, event.badEvents().size());
        assertEquals(1, event.badEvents().getFirst().observationCount());
        assertEquals(List.of(event.badEvents().getFirst().id()), event.badEventIds());
        assertThrows(UnsupportedOperationException.class, () -> event.badEvents().clear());
        assertThrows(IllegalStateException.class, () -> window.observe(result, run, key, START));
    }

    @Test void zeroCountUnknownAndCarryWindowsAreValid() {
        var empty = new UptimeWindow(UUID.randomUUID(), START, START, 100);
        empty.cover(START, START.plusSeconds((1) * 60), null, null, null, null, null, false);
        assertEquals(EventStatus.UNKNOWN, empty.close(START.plusSeconds((1) * 60)).status());
        var carry = new UptimeWindow(UUID.randomUUID(), START, START, 100);
        carry.cover(START, START.plusMillis((20) * 60), UUID.randomUUID(),
                new FailureKey(BadEventType.CHECK_FAILURE, "ERROR", null), START.minusMillis((10) * 60),
                START.minusMillis((10) * 60), "message", true);
        var event = carry.close(START.plusMillis((20) * 60));
        assertEquals(EventStatus.FAILED, event.status());
        assertEquals(0, event.failedChecks());
        assertEquals(0, event.badEvents().getFirst().observationCount());
    }

    @Test void uncoveredTailCannotBecomeImplicitSuccess() {
        var window = new UptimeWindow(UUID.randomUUID(), START, START, 100);
        window.cover(START, START.plusMillis((10) * 60), null, null, null, null, null, true);
        var event = window.close(START.plusSeconds((1) * 60));
        assertEquals(EventStatus.UNKNOWN, event.status());
        assertEquals(List.of(new TimeRange(START.plusMillis((10) * 60), START.plusSeconds((1) * 60))), event.unknownIntervals());
    }

    @Test void partialCountAloneDoesNotImplyUnknownAndUnknownRangesCoalesce() {
        var healthy = new UptimeWindow(UUID.randomUUID(), START, START, 100);
        healthy.cover(START, START.plusSeconds((1) * 60), null, null, null, null, null, true);
        var event = healthy.close(START.plusSeconds((1) * 60));
        assertEquals(EventStatus.SUCCESS, event.status());
        assertTrue(event.partialCoverage());
        var unknown = new UptimeWindow(UUID.randomUUID(), START, START, 100);
        unknown.cover(START, START.plusMillis((10) * 60), null, null, null, null, null, false);
        unknown.cover(START.plusMillis((10) * 60), START.plusMillis((20) * 60), null, null, null, null, null, false);
        assertEquals(List.of(new TimeRange(START, START.plusMillis((20) * 60))), unknown.close(START.plusMillis((20) * 60)).unknownIntervals());
        assertThrows(IllegalArgumentException.class, () -> new TimeRange(START, START.minusNanos(1)));
    }
    @Test void minuteAlignmentAndExclusiveObservationBoundaryAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> new UptimeWindow(UUID.randomUUID(), START.plusSeconds(1), START.plusSeconds(1), 6));
        var window = new UptimeWindow(UUID.randomUUID(), START, START, 6);
        Instant boundary = START.plusSeconds(60);
        var result = new CheckResult(UUID.randomUUID(), boundary, boundary, 0, CheckOutcome.SUCCESS, null);
        assertThrows(IllegalArgumentException.class, () -> window.observe(result, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> window.close(boundary.plusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new UptimeEvent(UUID.randomUUID(), UUID.randomUUID(),
                START.plusSeconds(1), START.plusSeconds(1), boundary, EventStatus.SUCCESS,
                0, 0, true, List.of(), List.of()));
    }

}
