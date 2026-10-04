package com.example.monitor.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.HealthCheckSucceeded;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;
import com.example.monitor.domain.TrackingEventStore;

/**
 * {@link TrackingEventStore} on the {@code tracking_event} table of the {@code monitor-db} module: one row
 * per event, numbered 1, 2, … per service. The {@code (service_id, version)} primary key is the
 * concurrency guard: if another writer appended first, the insert of the same version fails.
 */
public class JdbcTrackingEventStore implements TrackingEventStore {

	private static final String COLUMNS = "service_id, version, type, occurred_at, health_url, check_interval_ms, "
			+ "http_status, reason";

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	public JdbcTrackingEventStore(JdbcClient jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	@Override
	public void append(ServiceId id, long expectedVersion, List<TrackingEvent> events) {
		for (TrackingEvent event : events) {
			if (!event.serviceId().equals(id)) {
				throw new IllegalArgumentException("Event of " + event.serviceId() + " appended to " + id);
			}
		}
		try {
			transactions.executeWithoutResult(status -> {
				long actual = currentVersion(id);
				if (actual != expectedVersion) {
					throw new ConcurrencyException(id, expectedVersion, actual);
				}
				long version = expectedVersion;
				for (TrackingEvent event : events) {
					insert(++version, event);
				}
			});
		}
		catch (DuplicateKeyException e) {
			throw new ConcurrencyException(id, expectedVersion, currentVersion(id));
		}
	}

	@Override
	public List<TrackingEvent> load(ServiceId id) {
		return List.copyOf(jdbc.sql("SELECT " + COLUMNS + " FROM tracking_event WHERE service_id = ? ORDER BY version")
			.param(id.value())
			.query((rs, n) -> map(rs))
			.list());
	}

	@Override
	public List<ServiceId> serviceIds() {
		return List.copyOf(jdbc.sql("SELECT service_id FROM tracking_event WHERE version = 1 ORDER BY id")
			.query((rs, n) -> new ServiceId(rs.getObject(1, UUID.class)))
			.list());
	}

	private long currentVersion(ServiceId id) {
		return jdbc.sql("SELECT COALESCE(MAX(version), 0) FROM tracking_event WHERE service_id = ?")
			.param(id.value())
			.query(Long.class)
			.single();
	}

	private void insert(long version, TrackingEvent event) {
		String healthUrl = null;
		Long checkIntervalMs = null;
		Integer httpStatus = null;
		String reason = null;
			switch (event) {
			case HealthCheckSucceeded e -> {
			}
			case TrackingStarted e -> {
				healthUrl = e.healthUrl();
				checkIntervalMs = e.checkInterval().toMillis();
			}
			case Downtime e -> {
				httpStatus = e.httpStatus();
				reason = e.reason();
			}
			case InternalErrorHappened e -> {
				httpStatus = e.httpStatus();
				reason = e.reason();
			}
			case TrackingFinished e -> {
			}
		}
		jdbc.sql("INSERT INTO tracking_event (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
			.params(event.serviceId().value(), version, event.type(), Timestamp.from(event.occurredAt()), healthUrl,
					checkIntervalMs, httpStatus, reason)
			.update();
	}

	private static TrackingEvent map(ResultSet rs) throws SQLException {
		ServiceId id = new ServiceId(rs.getObject("service_id", UUID.class));
		var at = rs.getTimestamp("occurred_at").toInstant();
		Integer httpStatus = rs.getObject("http_status", Integer.class);
		String type = rs.getString("type");
		return switch (type) {
			case "HealthCheckSucceeded" -> new HealthCheckSucceeded(id, at);
			case "TrackingStarted" -> new TrackingStarted(id, rs.getString("health_url"),
					Duration.ofMillis(rs.getLong("check_interval_ms")), at);
			case "Downtime" -> new Downtime(id, httpStatus, rs.getString("reason"), at);
			case "InternalErrorHappened" -> new InternalErrorHappened(id, httpStatus, rs.getString("reason"), at);
			case "TrackingFinished" -> new TrackingFinished(id, at);
			default -> throw new IllegalStateException("Unknown event type " + type + " in stream " + id);
		};
	}

}
