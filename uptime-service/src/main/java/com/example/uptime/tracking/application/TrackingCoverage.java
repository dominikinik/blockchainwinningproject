package com.example.uptime.tracking.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.uptime.tracking.domain.TrackingSession;

public interface TrackingCoverage {
	List<TrackingSession> sessions(Instant from, Instant to);
	Optional<TrackingSession> latest();
}
