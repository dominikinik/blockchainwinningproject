package com.example.uptime.tracking.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.example.uptime.tracking.domain.*;
import com.example.uptime.aggregation.infrastructure.persistence.UptimeJsonConfiguration.PersistenceJson;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

@SuppressWarnings("unchecked")
class JdbcTrackingStoreTest {
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final PersistenceJson json = new PersistenceJson(JsonMapper.builder().build());
	private final JdbcTrackingStore store = new JdbcTrackingStore(jdbc, json);
	private final UUID id = UUID.randomUUID();
	private final Instant start = Instant.parse("2100-01-01T00:00:00.123456789Z");
	@Test
	void stopUpdatesOnlyStopFieldsNotStaleCommittedSnapshot() {
		Instant committed = start.plusNanos(5);
		Instant stopped = start.plusNanos(9);
		when(jdbc.queryForObject(eq("SELECT * FROM tracking_session WHERE id = ? FOR UPDATE"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(new TrackingSession(id, start, null, TrackingStatus.ACTIVE, committed));
		when(jdbc.update(startsWith("INSERT INTO tracking_event"), any(Object[].class))).thenReturn(1);
		store.saveStop(new TrackingSession(id, start, stopped, TrackingStatus.STOPPING, null),
				new TrackingEvent(UUID.randomUUID(), id, TrackingEventType.STOP, stopped, "USER_REQUEST"));
		verify(jdbc).update("UPDATE tracking_session SET status = 'STOPPING', stopped_at = ?, stop_second = ? WHERE id = ?",
				stopped.toString(), stopped.getEpochSecond(), id);
		verify(jdbc, never()).update(contains("committed_through"), any(Object[].class));
	}
	@Test
	void interruptedWithoutDataCutsAtExactStartAndCreatesRecoveryStop() {
		when(jdbc.query(startsWith("SELECT * FROM tracking_session WHERE status"), any(RowMapper.class)))
				.thenReturn(List.of(new TrackingSession(id, start, null, TrackingStatus.ACTIVE, null)));
		when(jdbc.update(startsWith("INSERT INTO tracking_event"), any(Object[].class))).thenReturn(1);
		store.recoverInterrupted();
		verify(jdbc).update("UPDATE tracking_session SET status = 'INTERRUPTED', stopped_at = ?, stop_second = ? WHERE id = ?",
				start.toString(), start.getEpochSecond(), id);
		verify(jdbc).update(startsWith("INSERT INTO tracking_event"), any(UUID.class), eq(id), eq("STOP"), contains("PROCESS_INTERRUPTED"));
	}

	@Test
	void recoveryRetainsExistingUserStopAndUsesCommittedCutoff() {
		Instant cutoff = start.plusNanos(7);
		when(jdbc.query(startsWith("SELECT * FROM tracking_session WHERE status"), any(RowMapper.class)))
				.thenReturn(List.of(new TrackingSession(id, start, start.plusSeconds(2), TrackingStatus.STOPPING, cutoff)));
		when(jdbc.query(startsWith("SELECT payload::text FROM tracking_event"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(List.of(new TrackingEvent(UUID.randomUUID(), id, TrackingEventType.STOP, start.plusSeconds(2), "USER_REQUEST")));
		store.recoverInterrupted();
		verify(jdbc).update("UPDATE tracking_session SET status = 'INTERRUPTED', stopped_at = ?, stop_second = ? WHERE id = ?",
				cutoff.toString(), cutoff.getEpochSecond(), id);
		verify(jdbc, never()).update(startsWith("INSERT"), any(Object[].class));
	}
	@Test
	void inclusivePointSelectionIncludesExactStartButExcludesExactStop() {
		Instant end = start.plusNanos(9);
		TrackingSession session = new TrackingSession(id, start, end, TrackingStatus.STOPPED, end);
		when(jdbc.query(startsWith("SELECT * FROM tracking_session WHERE start_second"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(List.of(session));
		assertThat(store.sessions(start, start)).containsExactly(session);
		assertThat(store.sessions(start.minusNanos(1), start)).containsExactly(session);
		assertThat(store.sessions(start.minusNanos(1), start.minusNanos(1))).isEmpty();
		assertThat(store.sessions(end.minusNanos(1), end.minusNanos(1))).containsExactly(session);
		assertThat(store.sessions(end, end)).isEmpty();
		clearInvocations(jdbc);
		assertThat(store.sessions(end, start)).isEmpty();
		verifyNoInteractions(jdbc);
	}

	@Test
	void activeSessionIncludesExactStartForPointQuery() {
		TrackingSession session = new TrackingSession(id, start, null, TrackingStatus.ACTIVE, null);
		when(jdbc.query(startsWith("SELECT * FROM tracking_session WHERE start_second"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(List.of(session));
		assertThat(store.sessions(start, start)).containsExactly(session);
		assertThat(store.sessions(start.plusNanos(1), start.plusNanos(1))).containsExactly(session);
	}

	@Test
	void completeStopRejectsNullOrInsufficientCommittedCoverage() {
		Instant end = start.plusNanos(9);
		when(jdbc.queryForObject(eq("SELECT * FROM tracking_session WHERE id = ? FOR UPDATE"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(new TrackingSession(id, start, end, TrackingStatus.STOPPING, null),
						new TrackingSession(id, start, end, TrackingStatus.STOPPING, end.minusNanos(1)));
		assertThatThrownBy(() -> store.completeStop(id)).isInstanceOf(IllegalStateException.class).hasMessageContaining("committed coverage");
		assertThatThrownBy(() -> store.completeStop(id)).isInstanceOf(IllegalStateException.class).hasMessageContaining("committed coverage");
		verify(jdbc, never()).update(anyString(), any(Object[].class));
	}

	@Test
	void completeStopAllowsExactCoverageAndZeroLengthAndIsIdempotent() {
		Instant end = start.plusNanos(9);
		when(jdbc.queryForObject(eq("SELECT * FROM tracking_session WHERE id = ? FOR UPDATE"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(new TrackingSession(id, start, end, TrackingStatus.STOPPING, end),
						new TrackingSession(id, start, start, TrackingStatus.STOPPING, null),
						new TrackingSession(id, start, end, TrackingStatus.STOPPED, end));
		store.completeStop(id);
		store.completeStop(id);
		store.completeStop(id);
		verify(jdbc, times(2)).update("UPDATE tracking_session SET status = 'STOPPED' WHERE id = ? AND status = 'STOPPING'", id);
	}

	@Test
	void completeStopDoesNotInventStopForActiveOrInterruptedSession() {
		when(jdbc.queryForObject(eq("SELECT * FROM tracking_session WHERE id = ? FOR UPDATE"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(new TrackingSession(id, start, null, TrackingStatus.ACTIVE, null),
						new TrackingSession(id, start, start, TrackingStatus.INTERRUPTED, null));
		assertThatThrownBy(() -> store.completeStop(id)).isInstanceOf(IllegalStateException.class).hasMessageContaining("ACTIVE");
		assertThatThrownBy(() -> store.completeStop(id)).isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERRUPTED");
		verify(jdbc, never()).update(anyString(), any(Object[].class));
	}

	@Test
	void stopRejectsConflictingImmutableStartBeforeWriting() {
		Instant end = start.plusNanos(9);
		when(jdbc.queryForObject(eq("SELECT * FROM tracking_session WHERE id = ? FOR UPDATE"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(new TrackingSession(id, start, null, TrackingStatus.ACTIVE, null));
		assertThatThrownBy(() -> store.saveStop(new TrackingSession(id, start.plusNanos(1), end, TrackingStatus.STOPPING, null),
				new TrackingEvent(UUID.randomUUID(), id, TrackingEventType.STOP, end, null)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicting tracking retry");
		verify(jdbc, never()).update(anyString(), any(Object[].class));
	}

	@Test
	void originalStartRetryDoesNotOverwriteAdvancedOrStoppedSession() {
		Instant end = start.plusNanos(9);
		TrackingSession original = new TrackingSession(id, start, null, TrackingStatus.ACTIVE, null);
		TrackingEvent event = new TrackingEvent(UUID.randomUUID(), id, TrackingEventType.START, start, null);
		when(jdbc.queryForObject(eq("SELECT * FROM tracking_session WHERE id = ? FOR UPDATE"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(new TrackingSession(id, start, end, TrackingStatus.STOPPED, end));
		when(jdbc.queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), any(Object[].class))).thenReturn(true);
		store.saveStart(original, event);
		verify(jdbc, never()).update(startsWith("UPDATE"), any(Object[].class));
	}

	@Test
	void lifecycleDuplicateWithChangedReasonIsRejected() {
		when(jdbc.queryForObject(eq("SELECT * FROM tracking_session WHERE id = ? FOR UPDATE"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(new TrackingSession(id, start, null, TrackingStatus.ACTIVE, null));
		assertThatThrownBy(() -> store.saveStart(new TrackingSession(id, start, null, TrackingStatus.ACTIVE, null),
				new TrackingEvent(UUID.randomUUID(), id, TrackingEventType.START, start, "changed")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicting tracking retry");
	}

	@Test
	void sessionAndLifecycleJsonPreserveNanoseconds() {
		TrackingSession session = new TrackingSession(id, start, start.plusNanos(9), TrackingStatus.STOPPED, start.plusNanos(9));
		TrackingEvent event = new TrackingEvent(UUID.randomUUID(), id, TrackingEventType.STOP, start.plusNanos(9), "USER_REQUEST");
		assertThat(json.read(json.write(session), TrackingSession.class)).isEqualTo(session);
		assertThat(json.read(json.write(event), TrackingEvent.class)).isEqualTo(event);
	}
}
