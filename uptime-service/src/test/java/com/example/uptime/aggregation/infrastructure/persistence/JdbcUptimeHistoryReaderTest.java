package com.example.uptime.aggregation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.example.uptime.aggregation.domain.EventStatus;
import com.example.uptime.aggregation.application.UptimeHistoryEntry;
import java.sql.ResultSet;
import com.example.uptime.aggregation.infrastructure.persistence.JdbcUptimeHistoryReader.StoredEvent;
import com.example.uptime.aggregation.infrastructure.persistence.UptimeJsonConfiguration.PersistenceJson;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

@SuppressWarnings("unchecked")
class JdbcUptimeHistoryReaderTest {
	@Test
	void retrievesBadEventIdsWithTheParentQueryWithoutPerWindowReads() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		var json = new PersistenceJson(JsonMapper.builder().build());
		var reader = new JdbcUptimeHistoryReader(jdbc, json);
		Instant start = Instant.parse("2026-10-03T12:00:00Z");
		StoredEvent event = new StoredEvent(UUID.randomUUID(), UUID.randomUUID(), start, start,
				start.plusSeconds(1), EventStatus.FAILED, 100, 98, false, List.of());
		List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
		ResultSet row = mock(ResultSet.class);
		when(row.getString(1)).thenReturn(json.write(event));
		when(row.getString(2)).thenReturn(json.write(ids));
		when(jdbc.query(contains("FROM uptime_event e"), any(RowMapper.class), any(Object[].class)))
				.thenAnswer(call -> List.of(((RowMapper<UptimeHistoryEntry>) call.getArgument(1)).mapRow(row, 0)));
		assertThat(reader.range(start, start.plusMillis(999))).singleElement()
				.satisfies(entry -> assertThat(entry.badEventIds()).containsExactlyElementsOf(ids));
		verify(jdbc, times(1)).query(contains("FROM uptime_event e"), any(RowMapper.class), any(Object[].class));
		verifyNoMoreInteractions(jdbc);
	}

	@Test
	void broadSecondSelectionIsFilteredByExactHalfOpenEdges() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		var reader = new JdbcUptimeHistoryReader(jdbc, new PersistenceJson(JsonMapper.builder().build()));
		Instant start = Instant.parse("2100-01-01T00:00:00.123456789Z");
		StoredEvent event = new StoredEvent(UUID.randomUUID(), UUID.randomUUID(), start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
				start, start.plusNanos(7), EventStatus.SUCCESS, 1, 1, true, List.of());
		when(jdbc.query(contains("FROM uptime_event e"), any(RowMapper.class), any(Object[].class)))
				.thenReturn(List.of(new UptimeHistoryEntry(event.id(), event.sessionId(), event.bucketStart(),
						event.windowStart(), event.windowEnd(), event.status(), event.totalChecks(), event.successfulChecks(),
						event.partialCoverage(), List.of(), event.unknownIntervals())));
		assertThat(reader.at(start.minusNanos(1))).isEmpty();
		assertThat(reader.at(start)).hasSize(1);
		assertThat(reader.at(start.plusNanos(6))).hasSize(1);
		assertThat(reader.at(start.plusNanos(7))).isEmpty();
		assertThat(reader.range(start.minusNanos(1), start)).hasSize(1);
				assertThat(reader.range(start.minusNanos(2), start.minusNanos(1))).isEmpty();
		assertThat(reader.range(start, start.plusNanos(1))).hasSize(1);
		assertThat(reader.range(start, start)).hasSize(1);
				assertThat(reader.range(start.plusNanos(7), start.plusNanos(7))).isEmpty();
				assertThat(reader.range(start.plusNanos(1), start)).isEmpty();
		assertThat(reader.range(start.plusNanos(7), start.plusSeconds(1))).isEmpty();
	}
}
