package com.example.uptime.aggregation.infrastructure.persistence;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import com.example.uptime.aggregation.application.UptimeHistoryEntry;
import com.example.uptime.aggregation.application.UptimeHistoryReader;
import com.example.uptime.aggregation.domain.BadEvent;
import com.example.uptime.aggregation.domain.EventStatus;
import com.example.uptime.aggregation.domain.TimeRange;
import com.example.uptime.aggregation.infrastructure.persistence.UptimeJsonConfiguration.PersistenceJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JdbcUptimeHistoryReader implements UptimeHistoryReader {
	private final JdbcTemplate jdbc;
	private final PersistenceJson json;
	public JdbcUptimeHistoryReader(JdbcTemplate jdbc, PersistenceJson json) {
		this.jdbc = jdbc;
		this.json = json;
	}
	@Override
	public List<UptimeHistoryEntry> at(Instant time) {
		return candidates(time, time).stream()
				.filter(e -> !time.isBefore(e.windowStart()) && time.isBefore(e.windowEnd())).toList();
	}
	@Override
	public List<UptimeHistoryEntry> range(Instant from, Instant to) {
		if (from.isAfter(to)) return List.of();
		return candidates(from, to).stream()
				.filter(e -> !e.windowStart().isAfter(to) && e.windowEnd().isAfter(from)).toList();
	}
	@Override
	public List<BadEvent> badEvents(UUID eventId) {
		return jdbc.query("SELECT payload::text FROM bad_event WHERE uptime_event_id = ? ORDER BY ordinal",
				(rs, row) -> json.read(rs.getString(1), BadEvent.class), eventId);
	}
	private List<UptimeHistoryEntry> candidates(Instant from, Instant to) {
		return jdbc.query("""
				SELECT e.payload::text,
				       COALESCE((SELECT jsonb_agg(b.id ORDER BY b.ordinal)
				                 FROM bad_event b WHERE b.uptime_event_id = e.id), '[]'::jsonb)::text
				FROM uptime_event e WHERE e.start_second <= ? AND e.end_second >= ?
				""", (rs, row) -> {
					StoredEvent e = json.read(rs.getString(1), StoredEvent.class);
					List<UUID> ids = java.util.Arrays.asList(json.read(rs.getString(2), UUID[].class));
					return new UptimeHistoryEntry(e.id(), e.sessionId(), e.bucketStart(), e.windowStart(),
							e.windowEnd(), e.status(), e.totalChecks(), e.successfulChecks(), e.partialCoverage(),
							ids, e.unknownIntervals());
				}, to.getEpochSecond(), from.getEpochSecond()).stream()
				.sorted(Comparator.comparing(UptimeHistoryEntry::windowStart).thenComparing(UptimeHistoryEntry::sessionId)
						.thenComparing(UptimeHistoryEntry::eventId)).toList();
	}
	public record StoredEvent(UUID id, UUID sessionId, Instant bucketStart, Instant windowStart, Instant windowEnd,
			EventStatus status, int totalChecks, int successfulChecks, boolean partialCoverage, List<TimeRange> unknownIntervals) {}
}
