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
        window.cover(START, START.plusMillis(50), run, key, START, START, "message", true);
        window.cover(START.plusMillis(50), START.plusSeconds(1), null, null, null, null, null, false);
        var event = window.close(START.plusSeconds(1));
        assertSame(event, window.close(START.plusSeconds(1)));
        assertEquals(1, event.failedChecks());
        assertEquals(1, event.badEvents().size());
        assertEquals(1, event.badEvents().getFirst().observationCount());
        assertEquals(List.of(event.badEvents().getFirst().id()), event.badEventIds());
        assertThrows(UnsupportedOperationException.class, () -> event.badEvents().clear());
        assertThrows(IllegalStateException.class, () -> window.observe(result, run, key, START));
    }

    @Test void zeroCountUnknownAndCarryWindowsAreValid() {
        var empty = new UptimeWindow(UUID.randomUUID(), START, START, 100);
        empty.cover(START, START.plusSeconds(1), null, null, null, null, null, false);
        assertEquals(EventStatus.UNKNOWN, empty.close(START.plusSeconds(1)).status());
        var carry = new UptimeWindow(UUID.randomUUID(), START, START, 100);
        carry.cover(START, START.plusMillis(20), UUID.randomUUID(),
                new FailureKey(BadEventType.CHECK_FAILURE, "ERROR", null), START.minusMillis(10),
                START.minusMillis(10), "message", true);
        var event = carry.close(START.plusMillis(20));
        assertEquals(EventStatus.FAILED, event.status());
        assertEquals(0, event.failedChecks());
        assertEquals(0, event.badEvents().getFirst().observationCount());
    }

    @Test void uncoveredTailCannotBecomeImplicitSuccess() {
        var window = new UptimeWindow(UUID.randomUUID(), START, START, 100);
        window.cover(START, START.plusMillis(10), null, null, null, null, null, true);
        var event = window.close(START.plusSeconds(1));
        assertEquals(EventStatus.UNKNOWN, event.status());
        assertEquals(List.of(new TimeRange(START.plusMillis(10), START.plusSeconds(1))), event.unknownIntervals());
    }

    @Test void partialCountAloneDoesNotImplyUnknownAndUnknownRangesCoalesce() {
        var healthy = new UptimeWindow(UUID.randomUUID(), START, START, 100);
        healthy.cover(START, START.plusSeconds(1), null, null, null, null, null, true);
        var event = healthy.close(START.plusSeconds(1));
        assertEquals(EventStatus.SUCCESS, event.status());
        assertTrue(event.partialCoverage());
        var unknown = new UptimeWindow(UUID.randomUUID(), START, START, 100);
        unknown.cover(START, START.plusMillis(10), null, null, null, null, null, false);
        unknown.cover(START.plusMillis(10), START.plusMillis(20), null, null, null, null, null, false);
        assertEquals(List.of(new TimeRange(START, START.plusMillis(20))), unknown.close(START.plusMillis(20)).unknownIntervals());
        assertThrows(IllegalArgumentException.class, () -> new TimeRange(START, START.minusNanos(1)));
    }
}
