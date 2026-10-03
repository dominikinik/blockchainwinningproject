package com.example.uptime.aggregation.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.example.uptime.aggregation.domain.BadEvent;

public interface UptimeHistoryReader {
	List<UptimeHistoryEntry> at(Instant time);
	List<UptimeHistoryEntry> range(Instant from, Instant to);
	List<BadEvent> badEvents(UUID eventId);
}
