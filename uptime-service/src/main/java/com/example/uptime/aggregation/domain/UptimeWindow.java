package com.example.uptime.aggregation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.uptime.checking.domain.CheckOutcome;
import com.example.uptime.checking.domain.CheckResult;

/** Mutable parent window; temporal sequencing is owned by the session use case. */
public final class UptimeWindow {
    private final UUID id = UUID.randomUUID();
    private final UUID sessionId;
    private final Instant bucketStart;
    private final Instant start;
    private final int expectedPerWindow;
    private final Set<UUID> checkIds = new HashSet<>();
    private final List<Fragment> fragments = new ArrayList<>();
    private final List<TimeRange> unknown = new ArrayList<>();
    private int successes;
    private Instant coveredThrough;
    private UptimeEvent closed;

    public UptimeWindow(UUID sessionId, Instant bucketStart, Instant start, int expectedPerWindow) {
        if (!bucketStart.equals(UptimeWindowPolicy.bucketStart(bucketStart)) || start.isBefore(bucketStart)
                || !start.isBefore(UptimeWindowPolicy.end(bucketStart)) || expectedPerWindow <= 0) {
            throw new IllegalArgumentException("Invalid window configuration");
        }
        this.sessionId = java.util.Objects.requireNonNull(sessionId);
        this.bucketStart = bucketStart;
        this.start = start;
        this.expectedPerWindow = expectedPerWindow;
        this.coveredThrough = start;
    }

    public Instant windowStart() { return start; }
    public boolean contains(UUID checkId) { return checkIds.contains(checkId); }

    public void observe(CheckResult result, UUID run, FailureKey key, Instant firstObservedAt) {
        ensureOpen();
        if (result.observedAt().isBefore(start) || !result.observedAt().isBefore(UptimeWindowPolicy.end(bucketStart))) {
            throw new IllegalArgumentException("Observation outside window");
        }
        if (!checkIds.add(result.checkId())) return;
        if (result.outcome() == CheckOutcome.SUCCESS) successes++;
        else {
            Fragment fragment = fragment(run, key, result.observedAt(), firstObservedAt,
                    result.observedAt(), result.failure().message());
            fragment.count++;
            fragment.last = result.observedAt();
        }
    }

    public void cover(Instant from, Instant to, UUID run, FailureKey key,
            Instant first, Instant last, String message, boolean known) {
        ensureOpen();
        if (!from.equals(coveredThrough) || to.isBefore(from) || to.isAfter(UptimeWindowPolicy.end(bucketStart))) {
            throw new IllegalArgumentException("Coverage must extend the window contiguously within its bounds");
        }
        if (!to.isAfter(from)) return;
        coveredThrough = to;
        if (!known) {
            if (!unknown.isEmpty() && unknown.getLast().end().equals(from)) {
                TimeRange prior = unknown.removeLast();
                unknown.add(new TimeRange(prior.start(), to));
            } else unknown.add(new TimeRange(from, to));
        } else if (key != null) {
            Fragment fragment = fragment(run, key, from, first, last, message);
            fragment.end = to;
        }
    }

    private Fragment fragment(UUID run, FailureKey key, Instant from, Instant first,
            Instant last, String message) {
        if (!fragments.isEmpty()) {
            Fragment prior = fragments.getLast();
            if (prior.run.equals(run) && prior.end.equals(from)) return prior;
        }
        Fragment fragment = new Fragment(run, key, from, first, last, message);
        fragments.add(fragment);
        return fragment;
    }

    public UptimeEvent close(Instant end) {
        if (closed != null) return closed;
        if (end.isBefore(coveredThrough) || end.isAfter(UptimeWindowPolicy.end(bucketStart))) {
            throw new IllegalArgumentException("Invalid close boundary");
        }
        // Uncovered tail is unknown, not implicit healthy time.
        if (coveredThrough.isBefore(end)) {
            cover(coveredThrough, end, null, null, null, null, null, false);
        }
        List<BadEvent> bad = fragments.stream().map(f -> new BadEvent(f.id, id, sessionId,
                f.key.type(), f.start, f.end, f.first, f.last, f.count, f.key, f.message)).toList();
        if (checkIds.isEmpty() && fragments.isEmpty() && end.equals(start)) {
                    unknown.add(new TimeRange(start, end));
                }
                long nanos = Duration.between(start, end).toNanos();
        long expected = (long) Math.ceil(nanos / (UptimeWindowPolicy.MILLIS * 1_000_000.0) * expectedPerWindow);
        boolean partial = !start.equals(bucketStart) || !end.equals(UptimeWindowPolicy.end(bucketStart))
                || !unknown.isEmpty() || checkIds.size() < expected;
        closed = new UptimeEvent(id, sessionId, bucketStart, start, end,
                !bad.isEmpty() ? EventStatus.FAILED : !unknown.isEmpty() ? EventStatus.UNKNOWN : EventStatus.SUCCESS,
                checkIds.size(), successes, partial, bad, unknown);
        return closed;
    }

    private void ensureOpen() {
        if (closed != null) throw new IllegalStateException("Window is closed");
    }

    private static final class Fragment {
        final UUID id = UUID.randomUUID();
        final UUID run;
        final FailureKey key;
        final Instant start;
        Instant end;
        final Instant first;
        Instant last;
        final String message;
        int count;
        Fragment(UUID run, FailureKey key, Instant start, Instant first, Instant last, String message) {
            this.run = run; this.key = key; this.start = start; this.end = start;
            this.first = first; this.last = last; this.message = message;
        }
    }
}
