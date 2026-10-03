package com.example.uptime.web;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.example.uptime.tracking.application.TrackingService;
import com.example.uptime.tracking.application.TrackingConflictException;
import com.example.uptime.tracking.domain.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TrackingControllerTest {
	private static final Instant START = Instant.parse("2026-10-03T12:00:00.123456789Z");
	private static final UUID SESSION = UUID.randomUUID();
	private TrackingService service;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		service = mock(TrackingService.class);
		mvc = MockMvcBuilders.standaloneSetup(new TrackingController(service))
				.setControllerAdvice(new ApiExceptionHandler()).build();
	}

	@Test
	void startReturns201AndStopReturns202WithParentState() throws Exception {
		when(service.start()).thenReturn(new TrackingSession(SESSION, START, null, TrackingStatus.ACTIVE, START));
		when(service.stop()).thenReturn(new TrackingSession(SESSION, START, START.plusSeconds(2),
				TrackingStatus.STOPPING, START));
		mvc.perform(post("/api/tracking/start")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(SESSION.toString()))
				.andExpect(jsonPath("$.startedAt").value(START.toString()))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(post("/api/tracking/stop")).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("STOPPING"))
				.andExpect(jsonPath("$.stoppedAt").value(START.plusSeconds(2).toString()));
		verify(service).start();
		verify(service).stop();
	}

	@Test
	void emptyStateReturnsStoppedDtoNotNull() throws Exception {
		when(service.state()).thenReturn(Optional.empty());
		mvc.perform(get("/api/tracking/state")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("STOPPED"));
		when(service.state()).thenReturn(Optional.of(new TrackingSession(SESSION, START, null, TrackingStatus.ACTIVE, START)));
		mvc.perform(get("/api/tracking/state")).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(SESSION.toString()));
	}

	@Test
	void eventsRequireSessionAndExposeExplicitStartStopRecords() throws Exception {
		when(service.events(SESSION)).thenReturn(List.of(
				new TrackingEvent(UUID.randomUUID(), SESSION, TrackingEventType.START, START, "Requested"),
				new TrackingEvent(UUID.randomUUID(), SESSION, TrackingEventType.STOP, START.plusSeconds(2), "Requested")));
		mvc.perform(get("/api/tracking/events").param("sessionId", SESSION.toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].type").value("START"))
				.andExpect(jsonPath("$[0].occurredAt").value(START.toString()))
				.andExpect(jsonPath("$[1].type").value("STOP"))
				.andExpect(jsonPath("$[1].sessionId").value(SESSION.toString()));
		mvc.perform(get("/api/tracking/events")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/tracking/events").param("sessionId", "invalid")).andExpect(status().isBadRequest());
		verify(service).events(SESSION);
	}

	@Test
	void repeatedStartAndStopMapParentConflictTo409() throws Exception {
		when(service.start()).thenThrow(new TrackingConflictException("Already tracking"));
		when(service.stop()).thenThrow(new TrackingConflictException("Not tracking"));
		for (String action : List.of("start", "stop")) {
			mvc.perform(post("/api/tracking/" + action)).andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("TRACKING_CONFLICT"));
		}
	}
}
