package com.example.uptime.aggregation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import com.example.uptime.aggregation.application.UptimeEventStore;
import com.example.uptime.aggregation.domain.*;
import com.example.uptime.aggregation.infrastructure.persistence.UptimeJsonConfiguration.PersistenceJson;
import com.example.uptime.tracking.application.TrackingStore;
import com.example.uptime.tracking.domain.*;
import com.example.uptime.tracking.infrastructure.JdbcTrackingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;

/** Isolated, opt-in: use a migrated test database with no concurrent service writers. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JdbcUptimeEventStorePostgresTest.Config.class)
@EnabledIfEnvironmentVariable(named = "RUN_PERSISTENCE_POSTGRES_TESTS", matches = "true")
class JdbcUptimeEventStorePostgresTest {
	@Autowired UptimeEventStore store;
	@Autowired TrackingStore tracking;
	@Autowired JdbcUptimeHistoryReader reader;
	@Autowired JdbcTemplate jdbc;
	private final UUID sessionId = UUID.randomUUID();
	private final UUID otherSessionId = UUID.randomUUID();
	private final Instant start = Instant.parse("2100-01-01T00:00:00.123456789Z");
	private TrackingSession session() { return new TrackingSession(sessionId, start, null, TrackingStatus.ACTIVE, null); }
	private void begin() { tracking.saveStart(session(), new TrackingEvent(UUID.randomUUID(), sessionId, TrackingEventType.START, start, null)); }
	@AfterEach
	void cleanup() {
		for (UUID id : List.of(sessionId, otherSessionId)) {
			jdbc.update("DELETE FROM bad_event WHERE session_id = ?", id);
			jdbc.update("DELETE FROM uptime_event WHERE session_id = ?", id);
			jdbc.update("DELETE FROM tracking_event WHERE session_id = ?", id);
			jdbc.update("DELETE FROM tracking_session WHERE id = ?", id);
		}
	}
	@Test
	void immutableRetryConflictRollsBackWholeBatchAndCommittedThrough() {
		begin();
		UptimeEvent original = JdbcUptimeEventStoreTest.success(sessionId, start);
		store.saveAll(List.of(original, original));
		UptimeEvent changed = new UptimeEvent(original.id(), sessionId, original.bucketStart(), start,
				original.windowEnd(), EventStatus.SUCCESS, 4, 4, true, List.of(), List.of());
		UptimeEvent later = JdbcUptimeEventStoreTest.success(sessionId, start.plusSeconds(1));
		assertThatThrownBy(() -> store.saveAll(List.of(later, changed))).isInstanceOf(IllegalStateException.class);
		assertThat(reader.range(start, start.plusSeconds(2))).hasSize(1);
		assertThat(tracking.find(sessionId).orElseThrow().committedThrough()).isEqualTo(original.windowEnd());
	}
	@Test
	void lifecycleUsesExactEdgesAndNeverOverwritesProgressFromStaleStop() {
		TrackingSession active = session();
		TrackingEvent begin = new TrackingEvent(UUID.randomUUID(), sessionId, TrackingEventType.START, start, null);
		tracking.saveStart(active, begin);
		tracking.saveStart(active, begin);
		assertThatThrownBy(() -> tracking.saveStart(new TrackingSession(otherSessionId, start, null, TrackingStatus.ACTIVE, null),
				new TrackingEvent(UUID.randomUUID(), otherSessionId, TrackingEventType.START, start, null))).isInstanceOf(RuntimeException.class);
		UptimeEvent original = JdbcUptimeEventStoreTest.success(sessionId, start);
		store.saveAll(List.of(original));
		Instant end = original.windowEnd();
		TrackingSession stopping = new TrackingSession(sessionId, start, end, TrackingStatus.STOPPING, null);
		TrackingEvent stop = new TrackingEvent(UUID.randomUUID(), sessionId, TrackingEventType.STOP, end, "USER_REQUEST");
		tracking.saveStop(stopping, stop);
		tracking.saveStop(stopping, stop);
		assertThat(tracking.find(sessionId).orElseThrow().committedThrough()).isEqualTo(end);
		tracking.completeStop(sessionId);
		tracking.saveStop(stopping, stop);
		assertThat(tracking.find(sessionId).orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(tracking.events(sessionId)).containsExactly(begin, stop);
		assertThat(tracking.sessions(start, start)).extracting(TrackingSession::id).containsExactly(sessionId);
		assertThat(tracking.sessions(start.minusNanos(1), start)).extracting(TrackingSession::id).containsExactly(sessionId);
		assertThat(tracking.sessions(start.minusNanos(1), start.minusNanos(1))).isEmpty();
		assertThat(tracking.sessions(end.minusNanos(1), end.minusNanos(1))).extracting(TrackingSession::id).containsExactly(sessionId);
		assertThat(tracking.sessions(end, end)).isEmpty();
		assertThat(reader.range(start, start)).hasSize(1);
		assertThat(reader.at(start.minusNanos(1))).isEmpty();
		assertThat(reader.at(start)).hasSize(1);
		assertThat(reader.at(end.minusNanos(1))).hasSize(1);
		assertThat(reader.at(end)).isEmpty();
		assertThat(reader.range(end, end.plusNanos(1))).isEmpty();
		assertThat(tracking.find(sessionId).orElseThrow().stoppedAt()).isEqualTo(end);
		// Two distinct sessions in the same second must both survive strict reads.
		tracking.saveStart(new TrackingSession(otherSessionId, end, null, TrackingStatus.ACTIVE, null),
				new TrackingEvent(UUID.randomUUID(), otherSessionId, TrackingEventType.START, end, null));
		store.saveAll(List.of(JdbcUptimeEventStoreTest.success(otherSessionId, end)));
		assertThat(reader.range(start, end.plusNanos(100))).hasSize(2);
		assertThat(reader.at(end)).extracting(e -> e.sessionId()).containsExactly(otherSessionId);
	}
	@Test
	void childRowsAreAtomicAndLosslessAndChangedChildrenConflict() {
		begin();
		UUID eventId = UUID.randomUUID();
		BadEvent child = new BadEvent(UUID.randomUUID(), eventId, sessionId, BadEventType.DOWNTIME,
				start, start.plusNanos(100), start.plusNanos(3), start.plusNanos(4), 1,
				new FailureKey(BadEventType.DOWNTIME, "DOWN", "failed"), "failed");
		BadEvent checkFailure = new BadEvent(UUID.randomUUID(), eventId, sessionId, BadEventType.CHECK_FAILURE,
				start.plusNanos(5), start.plusNanos(100), start.plusNanos(5), start.plusNanos(6), 1,
				new FailureKey(BadEventType.CHECK_FAILURE, "HTTP_TIMEOUT", "timeout"), "timed out");
		UptimeEvent event = new UptimeEvent(eventId, sessionId, start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), start,
				start.plusNanos(100), EventStatus.FAILED, 2, 0, true, List.of(child, checkFailure), List.of());
		store.saveAll(List.of(event, event));
		assertThat(reader.badEvents(eventId)).containsExactly(child, checkFailure);
		assertThat(reader.at(start).getFirst().badEventIds()).containsExactly(child.id(), checkFailure.id());
		BadEvent changed = new BadEvent(child.id(), eventId, sessionId, child.type(), child.windowStart(), child.windowEnd(),
				child.firstObservedAt(), child.lastObservedAt(), 1, child.failureKey(), "changed");
		UptimeEvent changedEvent = new UptimeEvent(eventId, sessionId, event.bucketStart(), start, event.windowEnd(), event.status(),
				2, 0, true, List.of(changed, checkFailure), List.of());
		assertThatThrownBy(() -> store.saveAll(List.of(changedEvent))).isInstanceOf(IllegalStateException.class);
		UptimeEvent fewerChildren = new UptimeEvent(eventId, sessionId, event.bucketStart(), start, event.windowEnd(), event.status(),
				2, 0, true, List.of(child), List.of());
		assertThatThrownBy(() -> store.saveAll(List.of(fewerChildren))).isInstanceOf(IllegalStateException.class);
		BadEvent extra = new BadEvent(UUID.randomUUID(), eventId, sessionId, child.type(), child.windowStart(), child.windowEnd(),
				child.firstObservedAt(), child.lastObservedAt(), 1, child.failureKey(), "extra");
		UptimeEvent moreChildren = new UptimeEvent(eventId, sessionId, event.bucketStart(), start, event.windowEnd(), event.status(),
				2, 0, true, List.of(child, checkFailure, extra), List.of());
		assertThatThrownBy(() -> store.saveAll(List.of(moreChildren))).isInstanceOf(IllegalStateException.class);
		UUID secondId = UUID.randomUUID();
		BadEvent duplicate = new BadEvent(child.id(), secondId, sessionId, child.type(), start.plusSeconds(1), start.plusSeconds(1).plusNanos(100),
				start.plusSeconds(1), start.plusSeconds(1), 1, child.failureKey(), "duplicate UUID");
		UptimeEvent invalid = new UptimeEvent(secondId, sessionId, start.plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
				start.plusSeconds(1), start.plusSeconds(1).plusNanos(100), EventStatus.FAILED, 1, 0, true, List.of(duplicate), List.of());
		assertThatThrownBy(() -> store.saveAll(List.of(invalid))).isInstanceOf(RuntimeException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM uptime_event WHERE id = ?", Integer.class, secondId)).isZero();
		assertThat(reader.badEvents(eventId)).containsExactly(child, checkFailure);
	}
	@Test
	void recoveryCutsAtCommittedDataAndRetainsUserStop() {
		begin();
		UptimeEvent event = JdbcUptimeEventStoreTest.success(sessionId, start);
		store.saveAll(List.of(event));
		TrackingEvent stop = new TrackingEvent(UUID.randomUUID(), sessionId, TrackingEventType.STOP, start.plusSeconds(3), "USER_REQUEST");
		tracking.saveStop(new TrackingSession(sessionId, start, stop.occurredAt(), TrackingStatus.STOPPING, null), stop);
		tracking.recoverInterrupted();
		tracking.recoverInterrupted();
		TrackingSession recovered = tracking.find(sessionId).orElseThrow();
		assertThat(recovered.status()).isEqualTo(TrackingStatus.INTERRUPTED);
		assertThat(recovered.stoppedAt()).isEqualTo(event.windowEnd());
		assertThat(tracking.events(sessionId)).contains(stop).hasSize(2);
		assertThatThrownBy(() -> store.saveAll(List.of(JdbcUptimeEventStoreTest.success(sessionId, start.plusSeconds(1)))))
				.isInstanceOf(IllegalStateException.class);
	}
	@Test
	void recoveryWithoutDataStopsAtStartAndCreatesExplicitDiagnosticStop() {
		begin();
		tracking.recoverInterrupted();
		assertThat(tracking.find(sessionId).orElseThrow().stoppedAt()).isEqualTo(start);
		assertThat(tracking.events(sessionId).getLast().reason()).isEqualTo("PROCESS_INTERRUPTED");
		assertThat(reader.at(start)).isEmpty();
	}
	@Test
	void completeStopWaitsForExactCommittedCutoffAndAllowsZeroLength() {
		begin();
		Instant boundary = start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(1);
		Instant cutoff = boundary.plusNanos(100);
		tracking.saveStop(new TrackingSession(sessionId, start, cutoff, TrackingStatus.STOPPING, null),
				new TrackingEvent(UUID.randomUUID(), sessionId, TrackingEventType.STOP, cutoff, null));
		assertThatThrownBy(() -> tracking.completeStop(sessionId)).isInstanceOf(IllegalStateException.class);
		assertThat(tracking.find(sessionId).orElseThrow().status()).isEqualTo(TrackingStatus.STOPPING);
		UptimeEvent first = new UptimeEvent(UUID.randomUUID(), sessionId, start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
				start, boundary, EventStatus.SUCCESS, 1, 1, true, List.of(), List.of());
		store.saveAll(List.of(first));
		assertThatThrownBy(() -> tracking.completeStop(sessionId)).isInstanceOf(IllegalStateException.class);
		store.saveAll(List.of(JdbcUptimeEventStoreTest.success(sessionId, boundary)));
		tracking.completeStop(sessionId);
		tracking.completeStop(sessionId);
		assertThat(tracking.find(sessionId).orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(tracking.find(sessionId).orElseThrow().committedThrough()).isEqualTo(cutoff);
		tracking.saveStart(new TrackingSession(otherSessionId, cutoff, null, TrackingStatus.ACTIVE, null),
				new TrackingEvent(UUID.randomUUID(), otherSessionId, TrackingEventType.START, cutoff, null));
		tracking.saveStop(new TrackingSession(otherSessionId, cutoff, cutoff, TrackingStatus.STOPPING, null),
				new TrackingEvent(UUID.randomUUID(), otherSessionId, TrackingEventType.STOP, cutoff, null));
		tracking.completeStop(otherSessionId);
		assertThat(tracking.find(otherSessionId).orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(tracking.find(otherSessionId).orElseThrow().committedThrough()).isNull();
	}

	@Test
	void lifecycleConflictsRollBackAndOriginalSnapshotsRemainRetryable() {
		TrackingSession active = session();
		TrackingEvent begin = new TrackingEvent(UUID.randomUUID(), sessionId, TrackingEventType.START, start, null);
		tracking.saveStart(active, begin);
		assertThatThrownBy(() -> tracking.saveStart(active,
				new TrackingEvent(begin.id(), sessionId, TrackingEventType.START, start, "changed")))
				.isInstanceOf(IllegalStateException.class);
		Instant cutoff = start.plusNanos(100);
		TrackingEvent stop = new TrackingEvent(UUID.randomUUID(), sessionId, TrackingEventType.STOP, cutoff, null);
		assertThatThrownBy(() -> tracking.saveStop(new TrackingSession(sessionId, start.plusNanos(1), cutoff, TrackingStatus.STOPPING, null), stop))
				.isInstanceOf(IllegalStateException.class);
		assertThat(tracking.find(sessionId).orElseThrow()).isEqualTo(active);
		assertThat(tracking.events(sessionId)).containsExactly(begin);
		TrackingSession stopping = new TrackingSession(sessionId, start, cutoff, TrackingStatus.STOPPING, null);
		tracking.saveStop(stopping, stop);
		assertThatThrownBy(() -> tracking.saveStop(stopping,
				new TrackingEvent(stop.id(), sessionId, TrackingEventType.STOP, cutoff, "changed")))
				.isInstanceOf(IllegalStateException.class);
		assertThat(tracking.events(sessionId)).containsExactly(begin, stop);
		store.saveAll(List.of(JdbcUptimeEventStoreTest.success(sessionId, start)));
		tracking.completeStop(sessionId);
		tracking.saveStart(active, begin);
		tracking.saveStop(stopping, stop);
		assertThat(tracking.find(sessionId).orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(tracking.find(sessionId).orElseThrow().committedThrough()).isEqualTo(cutoff);
		TrackingSession other = new TrackingSession(otherSessionId, cutoff, null, TrackingStatus.ACTIVE, null);
		assertThatThrownBy(() -> tracking.saveStart(other,
				new TrackingEvent(begin.id(), otherSessionId, TrackingEventType.START, cutoff, null)))
				.isInstanceOf(IllegalStateException.class);
		assertThat(tracking.find(otherSessionId)).isEmpty();
		assertThat(tracking.events(otherSessionId)).isEmpty();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class Config {
		@Bean DataSource dataSource() {
			return new DriverManagerDataSource(System.getenv().getOrDefault("UPTIME_TEST_DB_URL", "jdbc:postgresql://localhost:5432/uptime_test"),
					System.getenv().getOrDefault("UPTIME_DB_USER", "uptime"), System.getenv().getOrDefault("UPTIME_DB_PASSWORD", "uptime"));
		}
		@Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
		@Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
		@Bean PersistenceJson json() { return new PersistenceJson(JsonMapper.builder().build()); }
		@Bean UptimeEventStore store(JdbcTemplate jdbc, PersistenceJson json) { return new JdbcUptimeEventStore(jdbc, json); }
		@Bean TrackingStore tracking(JdbcTemplate jdbc, PersistenceJson json) { return new JdbcTrackingStore(jdbc, json); }
		@Bean JdbcUptimeHistoryReader reader(JdbcTemplate jdbc, PersistenceJson json) { return new JdbcUptimeHistoryReader(jdbc, json); }
	}
}
