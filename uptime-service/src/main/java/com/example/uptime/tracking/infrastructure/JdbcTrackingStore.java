package com.example.uptime.tracking.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.example.uptime.tracking.application.TrackingStore;
import com.example.uptime.tracking.domain.TrackingSession;
import com.example.uptime.tracking.domain.TrackingStatus;
import com.example.uptime.tracking.domain.TrackingEvent;
import com.example.uptime.tracking.domain.TrackingEventType;
import com.example.uptime.aggregation.infrastructure.persistence.UptimeJsonConfiguration.PersistenceJson;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTrackingStore implements TrackingStore {
	private final JdbcTemplate jdbc;
	private final PersistenceJson json;
	public JdbcTrackingStore(JdbcTemplate jdbc, PersistenceJson json) { this.jdbc = jdbc; this.json = json; }

	@Override
	@Transactional
	public void saveStart(TrackingSession session, TrackingEvent event) {
		if (session.status() != TrackingStatus.ACTIVE || session.stoppedAt() != null || session.committedThrough() != null
				|| event.type() != TrackingEventType.START || !event.sessionId().equals(session.id())
				|| !event.occurredAt().equals(session.startedAt())) throw new IllegalArgumentException("Invalid tracking start");
		jdbc.update("""
				INSERT INTO tracking_session (id, started_at, status, start_second) VALUES (?, ?, 'ACTIVE', ?)
				ON CONFLICT (id) DO NOTHING
				""", session.id(), session.startedAt().toString(), session.startedAt().getEpochSecond());
		TrackingSession existing = locked(session.id());
		if (!existing.startedAt().equals(session.startedAt())) throw conflict(session.id());
		persistEvent(event);
	}

	@Override
	@Transactional
	public void saveStop(TrackingSession session, TrackingEvent event) {
		if (session.status() != TrackingStatus.STOPPING || session.stoppedAt() == null
				|| event.type() != TrackingEventType.STOP || !event.sessionId().equals(session.id())
				|| !event.occurredAt().equals(session.stoppedAt())) throw new IllegalArgumentException("Invalid tracking stop");
		TrackingSession existing = locked(session.id());
		if (!existing.startedAt().equals(session.startedAt())) throw conflict(session.id());
		if (existing.status() == TrackingStatus.INTERRUPTED) {
			if (events(session.id()).stream().noneMatch(event::equals)) throw conflict(session.id());
			return;
		}
		if (existing.status() == TrackingStatus.ACTIVE) {
			if (session.stoppedAt().isBefore(existing.startedAt()) || (existing.committedThrough() != null
					&& session.stoppedAt().isBefore(existing.committedThrough()))) throw conflict(session.id());
			jdbc.update("UPDATE tracking_session SET status = 'STOPPING', stopped_at = ?, stop_second = ? WHERE id = ?",
					session.stoppedAt().toString(), session.stoppedAt().getEpochSecond(), session.id());
		} else if (!session.stoppedAt().equals(existing.stoppedAt())) throw conflict(session.id());
		persistEvent(event);
	}

	@Override
	@Transactional
	public void completeStop(UUID sessionId) {
		TrackingSession existing = locked(sessionId);
		if (existing.status() == TrackingStatus.STOPPED) return;
		if (existing.status() != TrackingStatus.STOPPING) {
			throw new IllegalStateException("Cannot complete tracking stop for " + sessionId + " in state " + existing.status());
		}
		if (existing.stoppedAt().isAfter(existing.startedAt()) && (existing.committedThrough() == null
				|| existing.committedThrough().isBefore(existing.stoppedAt()))) {
			throw new IllegalStateException("Cannot complete tracking stop for " + sessionId
					+ "; committed coverage has not reached " + existing.stoppedAt());
		}
		jdbc.update("UPDATE tracking_session SET status = 'STOPPED' WHERE id = ? AND status = 'STOPPING'", sessionId);
	}
	@Override
	public Optional<TrackingSession> find(UUID id) {
		return jdbc.query("SELECT * FROM tracking_session WHERE id = ?", this::session, id).stream().findFirst();
	}
	@Override
	public Optional<TrackingSession> latest() {
		return jdbc.query("SELECT * FROM tracking_session WHERE start_second >= (SELECT max(start_second) FROM tracking_session)", this::session)
				.stream().max(Comparator.comparing(TrackingSession::startedAt).thenComparing(TrackingSession::id));
	}
	@Override
	public List<TrackingSession> sessions(Instant from, Instant to) {
		if (from.isAfter(to)) return List.of();
		return jdbc.query("SELECT * FROM tracking_session WHERE start_second <= ? AND (stop_second IS NULL OR stop_second >= ?)",
				this::session, to.getEpochSecond(), from.getEpochSecond()).stream()
				.filter(s -> !s.startedAt().isAfter(to) && (s.stoppedAt() == null || s.stoppedAt().isAfter(from)))
				.sorted(Comparator.comparing(TrackingSession::startedAt).thenComparing(TrackingSession::id)).toList();
	}
	@Override
	public List<TrackingEvent> events(UUID sessionId) {
		return jdbc.query("SELECT payload::text FROM tracking_event WHERE session_id = ?", (rs, row) ->
				json.read(rs.getString(1), TrackingEvent.class), sessionId).stream()
				.sorted(Comparator.comparing(TrackingEvent::occurredAt).thenComparing(e -> e.type() == TrackingEventType.START ? 0 : 1)).toList();
	}

	@Override
	@Transactional
	@EventListener(ApplicationReadyEvent.class)
	public void recoverInterrupted() {
		List<TrackingSession> open = jdbc.query("SELECT * FROM tracking_session WHERE status IN ('ACTIVE', 'STOPPING') ORDER BY id FOR UPDATE", this::session);
		for (TrackingSession s : open) {
			Instant cutoff = s.committedThrough() == null ? s.startedAt() : s.committedThrough();
			jdbc.update("UPDATE tracking_session SET status = 'INTERRUPTED', stopped_at = ?, stop_second = ? WHERE id = ?",
					cutoff.toString(), cutoff.getEpochSecond(), s.id());
			if (events(s.id()).stream().noneMatch(e -> e.type() == TrackingEventType.STOP)) {
				persistEvent(new TrackingEvent(UUID.randomUUID(), s.id(), TrackingEventType.STOP, cutoff, "PROCESS_INTERRUPTED"));
			}
		}
	}

	private void persistEvent(TrackingEvent event) {
		String payload = json.write(event);
		int inserted = jdbc.update("INSERT INTO tracking_event (id, session_id, type, payload) VALUES (?, ?, ?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING",
				event.id(), event.sessionId(), event.type().name(), payload);
		if (inserted == 0 && !Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM tracking_event WHERE id = ? AND payload = CAST(? AS jsonb))", Boolean.class, event.id(), payload))) {
			throw conflict(event.id());
		}
	}
	private TrackingSession locked(UUID id) {
		return jdbc.queryForObject("SELECT * FROM tracking_session WHERE id = ? FOR UPDATE", this::session, id);
	}
	private TrackingSession session(ResultSet rs, int row) throws SQLException {
		return new TrackingSession(rs.getObject("id", UUID.class), Instant.parse(rs.getString("started_at")),
				instant(rs.getString("stopped_at")), TrackingStatus.valueOf(rs.getString("status")), instant(rs.getString("committed_through")));
	}
	private static Instant instant(String value) { return value == null ? null : Instant.parse(value); }
	private static IllegalStateException conflict(UUID id) { return new IllegalStateException("Conflicting tracking retry " + id); }
}
