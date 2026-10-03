package com.example.uptime.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.example.uptime.aggregation.application.*;
import com.example.uptime.aggregation.domain.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UptimeControllerTest {
	private static final Instant START = Instant.parse("2026-10-03T12:00:00Z");
	private UptimeQueryService query;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		query = mock(UptimeQueryService.class);
		mvc = MockMvcBuilders.standaloneSetup(new UptimeController(query))
				.setControllerAdvice(new ApiExceptionHandler()).build();
	}

	@Test
	void summaryPreservesUnknownNullDownAndExactBounds() throws Exception {
		UUID session = UUID.randomUUID();
		Instant from = START.plusNanos(123456789);
		UptimePoint point = new UptimePoint(START, null, session, from, START.plusSeconds(1), "UNKNOWN", true);
		when(query.range(from, START.plusSeconds(1))).thenReturn(List.of(point));
		when(query.at(from)).thenReturn(point);
		mvc.perform(get("/api/uptime").param("from", from.toString()).param("to", START.plusSeconds(1).toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].time").value(START.toString()))
				.andExpect(jsonPath("$[0].sessionId").value(session.toString()))
				.andExpect(jsonPath("$[0].down").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$[0].status").value("UNKNOWN"));
		mvc.perform(get("/api/uptime/at").param("time", from.toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.windowStart").value(from.toString()));
		verify(query).range(from, START.plusSeconds(1));
		verify(query).at(from);
	}

	@Test
	void detailExposesBothBadEventTypesAndExactIsoTimes() throws Exception {
		UUID event = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		Instant observed = START.plusNanos(234567890);
		List<BadEvent> bad = java.util.Arrays.stream(BadEventType.values()).map(type ->
				new BadEvent(UUID.randomUUID(), event, session, type, START, START.plusSeconds(1),
						observed, observed, 1, new FailureKey(type, "HEALTH_DOWN", "Not UP"), "Not UP")).toList();
		when(query.event(observed)).thenReturn(new UptimeEventDetails(event, session, START, START,
				START.plusSeconds(1), "FAILED", 3, 1, 2, true,
				bad.stream().map(BadEvent::id).toList(), bad, List.of(new TimeRange(START, observed))));
		mvc.perform(get("/api/uptime/event").param("time", observed.toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.eventId").value(event.toString()))
				.andExpect(jsonPath("$.sessionId").value(session.toString()))
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failedChecks").value(2))
				.andExpect(jsonPath("$.badEventIds.length()").value(2))
				.andExpect(jsonPath("$.badEvents[0].type").value("DOWNTIME"))
				.andExpect(jsonPath("$.badEvents[1].type").value("CHECK_FAILURE"))
				.andExpect(jsonPath("$.badEvents[0].firstObservedAt").value(observed.toString()))
				.andExpect(jsonPath("$.unknownIntervals[0].end").value(observed.toString()));
	}

	@Test
	void pendingDetailDoesNotFabricateSuccessOrChildren() throws Exception {
		when(query.event(START)).thenReturn(new UptimeEventDetails(null, UUID.randomUUID(), START, START,
				START.plusSeconds(1), "PENDING", 0, 0, 0, true, List.of(), List.of(), List.of()));
		mvc.perform(get("/api/uptime/event").param("time", START.toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.eventId").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.badEvents").isEmpty());
	}

	@Test
	void outsideCoverageHasStable422CodeAndGapBounds() throws Exception {
		when(query.range(null, null)).thenThrow(new OutsideTrackingCoverageException(START, START.plusSeconds(1)));
		mvc.perform(get("/api/uptime")).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("OUTSIDE_TRACKING_COVERAGE"))
				.andExpect(jsonPath("$.detail").value("Requested interval is outside tracking coverage"))
				.andExpect(jsonPath("$.from").value(START.toString()))
				.andExpect(jsonPath("$.to").value(START.plusSeconds(1).toString()));
	}

	@Test
	void invalidRangeAndFutureReturn400() throws Exception {
		when(query.range(null, null)).thenThrow(new IllegalArgumentException("Invalid range"));
		when(query.at(START)).thenThrow(new IllegalArgumentException("Query time must not be in the future"));
		mvc.perform(get("/api/uptime")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/uptime/at").param("time", START.toString())).andExpect(status().isBadRequest());
	}

	@Test
	void databaseFailuresAreSanitized503ForEveryReadEndpoint() throws Exception {
		DataAccessResourceFailureException error = new DataAccessResourceFailureException("password=secret");
		when(query.range(null, null)).thenThrow(error);
		when(query.at(START)).thenThrow(error);
		when(query.event(START)).thenThrow(error);
		for (String path : List.of("/api/uptime", "/api/uptime/at", "/api/uptime/event")) {
			mvc.perform(get(path).param("time", START.toString())).andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.detail").value("Uptime history is temporarily unavailable"));
		}
	}

	@Test
	void eventRequiresValidTime() throws Exception {
		mvc.perform(get("/api/uptime/event")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/uptime/event").param("time", "not-an-instant")).andExpect(status().isBadRequest());
		verifyNoInteractions(query);
	}
}
