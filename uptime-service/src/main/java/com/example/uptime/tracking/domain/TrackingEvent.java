package com.example.uptime.tracking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TrackingEvent(UUID id, UUID sessionId, TrackingEventType type, Instant occurredAt, String reason) {
	public TrackingEvent {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(occurredAt, "occurredAt");
	}
}
