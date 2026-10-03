package com.example.uptime.aggregation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.uptime.aggregation.domain.*;
import com.example.uptime.aggregation.infrastructure.persistence.UptimeJsonConfiguration.PersistenceJson;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

@SuppressWarnings("unchecked")
class JdbcUptimeEventStoreTest {
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final PersistenceJson json = new PersistenceJson(JsonMapper.builder().build());
	private final JdbcUptimeEventStore store = new JdbcUptimeEventStore(jdbc, json);
	static UptimeEvent success(UUID session, Instant start) {
		return new UptimeEvent(UUID.randomUUID(), session, start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
				start, start.plusNanos(100), EventStatus.SUCCESS, 3, 3, true, List.of(), List.of());
	}
	@Test
	void equalRetryChecksPayloadAndChildrenWithoutOverwritingParent() {
		UptimeEvent event = success(UUID.randomUUID(), Instant.parse("2001-01-01T00:00:00.123456789Z"));
		when(jdbc.queryForObject(eq(JdbcUptimeEventStore.MATCH), eq(Boolean.class), any(Object[].class))).thenReturn(true);
		when(jdbc.queryForObject(eq("SELECT committed_through FROM tracking_session WHERE id = ?"), eq(String.class), any(Object[].class)))
				.thenReturn(event.windowEnd().toString());
		store.saveAll(List.of(event));
		verify(jdbc).queryForObject(eq(JdbcUptimeEventStore.MATCH), eq(Boolean.class), any(Object[].class));
		verify(jdbc, never()).update(startsWith("UPDATE"), any(Object[].class));
	}
	@Test
	void changedRetryIsRejected() {
		assertThatThrownBy(() -> store.saveAll(List.of(success(UUID.randomUUID(), Instant.parse("2001-01-01T00:00:00Z")))))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicting finalized uptime event");
	}
	@Test
	void preciseWindowAcceptedAndCommitAdvancesWithoutSnapshotOverwrite() {
		UptimeEvent event = success(UUID.randomUUID(), Instant.parse("2001-01-01T00:00:00.123456789Z"));
		when(jdbc.update(eq(JdbcUptimeEventStore.INSERT), any(Object[].class))).thenReturn(1);
		when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of("status", "ACTIVE", "started_at", event.windowStart().toString()));
		store.saveAll(List.of(event));
		verify(jdbc).update("UPDATE tracking_session SET committed_through = ? WHERE id = ?", event.windowEnd().toString(), event.sessionId());
	}
	@Test
	void identicalParentPayloadDoesNotPermitRemovingOrAddingChildRows() {
		UUID eventId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		Instant start = Instant.parse("2001-01-01T00:00:00.123456789Z");
		BadEvent first = new BadEvent(UUID.randomUUID(), eventId, sessionId, BadEventType.DOWNTIME,
				start, start.plusNanos(100), start.plusNanos(1), start.plusNanos(2), 1,
				new FailureKey(BadEventType.DOWNTIME, "DOWN", null), "down");
		BadEvent second = new BadEvent(UUID.randomUUID(), eventId, sessionId, BadEventType.CHECK_FAILURE,
				start, start.plusNanos(100), start.plusNanos(3), start.plusNanos(4), 1,
				new FailureKey(BadEventType.CHECK_FAILURE, "HTTP_TIMEOUT", null), "timeout");
		BadEvent extra = new BadEvent(UUID.randomUUID(), eventId, sessionId, first.type(), first.windowStart(), first.windowEnd(),
				first.firstObservedAt(), first.lastObservedAt(), 1, first.failureKey(), "extra");
		when(jdbc.queryForObject(eq(JdbcUptimeEventStore.MATCH), eq(Boolean.class), any(Object[].class))).thenReturn(true);
		when(jdbc.query(startsWith("SELECT payload::text FROM bad_event"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(List.of(json.write(first), json.write(second)));
		for (List<BadEvent> children : List.of(List.of(first), List.of(first, second, extra))) {
			UptimeEvent retry = new UptimeEvent(eventId, sessionId, start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), start,
					start.plusNanos(100), EventStatus.FAILED, 2, 0, true, children, List.of());
			assertThatThrownBy(() -> store.saveAll(List.of(retry))).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Conflicting finalized uptime event");
		}
		verify(jdbc, never()).update(startsWith("INSERT INTO bad_event"), any(Object[].class));
		verify(jdbc, never()).update(startsWith("UPDATE"), any(Object[].class));
	}

	@Test
	void committingOlderEventDoesNotRegressCommittedThrough() {
		UptimeEvent event = success(UUID.randomUUID(), Instant.parse("2001-01-01T00:00:00.123456789Z"));
		when(jdbc.update(eq(JdbcUptimeEventStore.INSERT), any(Object[].class))).thenReturn(1);
		when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of("status", "ACTIVE", "started_at", event.windowStart().toString()));
		when(jdbc.queryForObject(eq("SELECT committed_through FROM tracking_session WHERE id = ?"), eq(String.class), any(Object[].class)))
				.thenReturn(event.windowEnd().plusSeconds(1).toString());
		store.saveAll(List.of(event));
		verify(jdbc, never()).update(startsWith("UPDATE"), any(Object[].class));
	}

	@Test
	void unknownIntervalJsonRoundTripsExactNanoseconds() {
		Instant start = Instant.parse("2001-01-01T00:00:00.123456789Z");
		UptimeEvent event = new UptimeEvent(UUID.randomUUID(), UUID.randomUUID(), start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
				start, start.plusNanos(7), EventStatus.UNKNOWN, 0, 0, true, List.of(), List.of(new TimeRange(start, start.plusNanos(7))));
		assertThat(json.read(json.write(event), UptimeEvent.class)).isEqualTo(event);
		assertThat(json.eventPayload(event)).contains(start.toString(), start.plusNanos(7).toString());
	}

	@Test
	void jsonRoundTripsNanosecondsAndParentHasNoChildArray() {
		UptimeEvent event = success(UUID.randomUUID(), Instant.parse("2001-01-01T00:00:00.123456789Z"));
		assertThat(json.read(json.write(event), UptimeEvent.class)).isEqualTo(event);
		assertThat(json.eventPayload(event)).doesNotContain("badEvents", "badEventIds")
				.contains(event.windowStart().toString(), event.windowEnd().toString());
		UptimeRecord legacy = new UptimeRecord(event.windowStart(), false, 1, 0);
		assertThat(legacy.getFailures()).isNull();
	}
}
