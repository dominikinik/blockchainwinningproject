package com.example.uptime.aggregation.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import com.example.uptime.aggregation.application.UptimeEventStore;
import com.example.uptime.aggregation.domain.UptimeEvent;
import com.example.uptime.aggregation.infrastructure.persistence.UptimeJsonConfiguration.PersistenceJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcUptimeEventStore implements UptimeEventStore {
	static final String INSERT = """
			INSERT INTO uptime_event (id, session_id, bucket_start, start_second, end_second, payload)
			VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (id) DO NOTHING
			""";
	static final String MATCH = "SELECT payload = CAST(? AS jsonb) FROM uptime_event WHERE id = ?";
	private final JdbcTemplate jdbc;
	private final PersistenceJson json;

	public JdbcUptimeEventStore(JdbcTemplate jdbc, PersistenceJson json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	@Transactional
	public void saveAll(List<UptimeEvent> events) {
		// Deterministic locking avoids deadlocks for batches spanning multiple sessions.
		List<UptimeEvent> batch = List.copyOf(events);
		batch.stream().map(UptimeEvent::sessionId).distinct().sorted().forEach(id ->
				jdbc.queryForObject("SELECT id FROM tracking_session WHERE id = ? FOR UPDATE", java.util.UUID.class, id));
		for (UptimeEvent event : batch) {
			String payload = json.eventPayload(event);
			int inserted = jdbc.update(INSERT, event.id(), event.sessionId(), event.bucketStart().toString(),
					event.windowStart().getEpochSecond(), event.windowEnd().getEpochSecond(), payload);
			if (inserted == 0 && !Boolean.TRUE.equals(jdbc.queryForObject(MATCH, Boolean.class, payload, event.id()))) {
				throw conflict(event.id());
			}
			if (inserted == 0) {
				List<String> existing = jdbc.query("SELECT payload::text FROM bad_event WHERE uptime_event_id = ? ORDER BY ordinal",
						(rs, row) -> rs.getString(1), event.id());
				if (existing.size() != event.badEvents().size()) throw conflict(event.id());
				for (int i = 0; i < existing.size(); i++) {
					if (!json.read(existing.get(i), com.example.uptime.aggregation.domain.BadEvent.class).equals(event.badEvents().get(i))) {
						throw conflict(event.id());
					}
				}
			} else {
				var bounds = jdbc.queryForMap("SELECT started_at, stopped_at, status FROM tracking_session WHERE id = ?", event.sessionId());
				String status = (String) bounds.get("status");
				String stopped = (String) bounds.get("stopped_at");
				if ("STOPPED".equals(status) || "INTERRUPTED".equals(status)
						|| event.windowStart().isBefore(Instant.parse((String) bounds.get("started_at")))
						|| (stopped != null && event.windowEnd().isAfter(Instant.parse(stopped)))) {
					throw new IllegalStateException("Uptime event outside writable tracking session: " + event.id());
				}
				for (int i = 0; i < event.badEvents().size(); i++) {
					var bad = event.badEvents().get(i);
					if (!bad.uptimeEventId().equals(event.id()) || !bad.sessionId().equals(event.sessionId())) {
						throw new IllegalArgumentException("Bad event parent/session mismatch: " + bad.id());
					}
					jdbc.update("INSERT INTO bad_event (id, uptime_event_id, session_id, ordinal, payload) VALUES (?, ?, ?, ?, CAST(? AS jsonb))",
							bad.id(), event.id(), event.sessionId(), i, json.write(bad));
				}
			}
			String committed = jdbc.queryForObject("SELECT committed_through FROM tracking_session WHERE id = ?", String.class, event.sessionId());
			if (committed == null || Instant.parse(committed).isBefore(event.windowEnd())) {
				jdbc.update("UPDATE tracking_session SET committed_through = ? WHERE id = ?", event.windowEnd().toString(), event.sessionId());
			}
		}
	}

	private static IllegalStateException conflict(java.util.UUID id) {
		return new IllegalStateException("Conflicting finalized uptime event " + id + "; existing history was not overwritten");
	}
}
