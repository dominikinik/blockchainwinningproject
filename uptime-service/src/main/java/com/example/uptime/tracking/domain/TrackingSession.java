package com.example.uptime.tracking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TrackingSession(UUID id, Instant startedAt, Instant stoppedAt, TrackingStatus status,
		Instant committedThrough) {
	public TrackingSession {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(startedAt, "startedAt");
		Objects.requireNonNull(status, "status");
		if ((status == TrackingStatus.ACTIVE) != (stoppedAt == null)) {
			throw new IllegalArgumentException("Only an active tracking session has no stop timestamp");
		}
		if (stoppedAt != null && stoppedAt.isBefore(startedAt)) {
			throw new IllegalArgumentException("Tracking stop must not precede its start");
		}
		if (committedThrough != null && (committedThrough.isBefore(startedAt)
				|| stoppedAt != null && committedThrough.isAfter(stoppedAt))) {
			throw new IllegalArgumentException("Committed coverage must lie within the tracking session");
		}
	}

	public boolean overlaps(Instant from, Instant to) {
		return !startedAt.isAfter(to) && (stoppedAt == null || stoppedAt.isAfter(from));
	}
}
