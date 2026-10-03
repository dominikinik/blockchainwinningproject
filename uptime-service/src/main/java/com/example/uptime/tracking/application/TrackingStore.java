package com.example.uptime.tracking.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.uptime.tracking.domain.TrackingEvent;
import com.example.uptime.tracking.domain.TrackingSession;

public interface TrackingStore {
	void saveStart(TrackingSession session, TrackingEvent event);
	void saveStop(TrackingSession session, TrackingEvent event);
	void completeStop(UUID sessionId);
	Optional<TrackingSession> find(UUID sessionId);
	Optional<TrackingSession> latest();
	/** Select sessions intersecting inclusive original query bounds; session ends remain exclusive. */
	List<TrackingSession> sessions(Instant from, Instant to);
	List<TrackingEvent> events(UUID sessionId);
	void recoverInterrupted();
}
