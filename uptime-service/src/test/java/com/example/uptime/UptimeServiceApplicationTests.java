package com.example.uptime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.uptime.monitor.MonitoringScheduler;
import com.example.uptime.aggregation.application.*;
import com.example.uptime.tracking.application.*;
import com.example.uptime.tracking.domain.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import com.example.uptime.state.ApplicationStateService;

import com.example.uptime.aggregation.infrastructure.persistence.UptimeRecord;
import com.example.uptime.aggregation.infrastructure.persistence.UptimeRecordRepository;

/**
 * Starts the whole application against in-memory H2 (test profile) and exercises it over HTTP. PostgreSQL-specific persistence ports are replaced at the module boundary.
 * Scheduled jobs are registered, then cancelled so tests drive them with a controlled clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UptimeServiceApplicationTests {

	@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
	static class TestClockConfiguration {
		@org.springframework.context.annotation.Bean
		@org.springframework.context.annotation.Primary
		com.example.uptime.support.MutableClock testClock() {
			return new com.example.uptime.support.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
		}
	}

	@Autowired com.example.uptime.support.MutableClock clock;
	@MockitoBean TrackingStore trackingStore;
	@MockitoBean UptimeEventStore eventStore;
	@MockitoBean UptimeHistoryReader history;
	@Autowired TrackingService tracking;

	@Autowired
	MockMvc mvc;

	@Autowired
	UptimeRecordRepository repository;

	@Autowired
	ApplicationStateService state;

	@Autowired
	ApplicationContext context;

	private static final java.util.concurrent.atomic.AtomicLong TEST_SEQUENCE = new java.util.concurrent.atomic.AtomicLong();
	private Instant now;
	private java.util.UUID ownedSession;
	private TrackingFixture.MemoryTrackingStore memory;
	private final java.util.List<com.example.uptime.aggregation.domain.UptimeEvent> saved = new java.util.concurrent.CopyOnWriteArrayList<>();

	@org.junit.jupiter.api.BeforeEach
	void preparePorts() {
		// Retain task registration for wiring assertions, but drive jobs and time manually.
		context.getBeansOfType(ScheduledTaskHolder.class).values().forEach(holder -> holder.getScheduledTasks().forEach(ScheduledTask::cancel));
		now = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(TEST_SEQUENCE.getAndIncrement() * 60);
		clock.set(now);
		memory = new TrackingFixture.MemoryTrackingStore();
		saved.clear();
		doAnswer(c -> { memory.saveStart(c.getArgument(0), c.getArgument(1)); return null; }).when(trackingStore).saveStart(any(), any());
		doAnswer(c -> { memory.saveStop(c.getArgument(0), c.getArgument(1)); return null; }).when(trackingStore).saveStop(any(), any());
		doAnswer(c -> { memory.completeStop(c.getArgument(0)); return null; }).when(trackingStore).completeStop(any());
		when(trackingStore.find(any())).thenAnswer(c -> memory.find(c.getArgument(0)));
		when(trackingStore.latest()).thenAnswer(c -> memory.latest());
		when(trackingStore.sessions(any(), any())).thenAnswer(c -> memory.sessions(c.getArgument(0), c.getArgument(1)));
		when(trackingStore.events(any())).thenAnswer(c -> memory.events(c.getArgument(0)));
		doAnswer(c -> {
			List<com.example.uptime.aggregation.domain.UptimeEvent> events = c.getArgument(0);
			for (var event : events) {
				if (saved.stream().noneMatch(e -> e.id().equals(event.id()))) saved.add(event);
				memory.advance(event.sessionId(), event.windowEnd());
			}
			return null;
		}).when(eventStore).saveAll(anyList());
		when(history.at(any())).thenAnswer(c -> entries(c.getArgument(0), c.getArgument(0)));
		when(history.range(any(), any())).thenAnswer(c -> entries(c.getArgument(0), c.getArgument(1)));
		when(history.badEvents(any())).thenAnswer(c -> saved.stream().filter(e -> e.id().equals(c.getArgument(0))).flatMap(e -> e.badEvents().stream()).toList());
	}

	private List<UptimeHistoryEntry> entries(Instant from, Instant to) {
		return saved.stream().filter(e -> !e.windowStart().isAfter(to) && e.windowEnd().isAfter(from))
			.map(e -> new UptimeHistoryEntry(e.id(), e.sessionId(), e.bucketStart(), e.windowStart(), e.windowEnd(), e.status(), e.totalChecks(), e.successfulChecks(), e.partialCoverage(), e.badEvents().stream().map(b -> b.id()).toList(), e.unknownIntervals())).toList();
	}

	@AfterEach
	void reset() {
		try {
			if (ownedSession == null) return;
			if (tracking.state().map(s -> s.id().equals(ownedSession) && s.status() == TrackingStatus.ACTIVE).orElse(false)) {
							now = now.plusMillis(10);
							clock.set(now);
							tracking.stop();
						}
			if (tracking.state().map(s -> s.id().equals(ownedSession) && s.status() == TrackingStatus.STOPPING).orElse(false)) tracking.flush();
		} finally { state.start(); }
	}

	@Test
	void applicationStartsWithAllComponentsAndUp() {
		assertThat(context.getBean(MonitoringScheduler.class)).isNotNull();
		assertThat(context.getBean(UptimeQueryService.class)).isNotNull();
		assertThat(context.getBean(Clock.class).getZone()).isEqualTo(Clock.systemUTC().getZone());
		UptimeProperties properties = context.getBean(UptimeProperties.class);
		assertThat(properties.sampleIntervalMs()).isEqualTo(10000);
		assertThat(properties.flushIntervalMs()).isEqualTo(1000);
		assertThat(properties.maxRangeSeconds()).isEqualTo(86400);
		assertThat(properties.defaultRangeSeconds()).isEqualTo(300);
		assertThat(state.isUp()).isTrue();
	}

	@Test
	void samplerAndFlushAreScheduled() {
		List<String> tasks = context.getBeansOfType(ScheduledTaskHolder.class)
			.values()
			.stream()
			.map(ScheduledTaskHolder::getScheduledTasks)
			.flatMap(Collection::stream)
			.map(ScheduledTask::toString)
			.toList();
		assertThat(tasks).anyMatch(t -> t.contains("MonitoringScheduler.sample"));
		assertThat(tasks).anyMatch(t -> t.contains("MonitoringScheduler.flush"));
	}

	@Test
	void stopAndStartSwitchHealthEndpointAndState() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/api/application/state")).andExpect(jsonPath("$.status").value("UP"));

		mvc.perform(post("/api/application/stop")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(get("/api/application/state")).andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(get("/actuator/health"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value("DOWN"))
			.andExpect(jsonPath("$.components.applicationState.details.reason").value("stopped via API"));

		mvc.perform(post("/api/application/start")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void repeatedStopAndStartAreIdempotent() throws Exception {
		mvc.perform(post("/api/application/stop"));
		mvc.perform(post("/api/application/stop")).andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(post("/api/application/start"));
		mvc.perform(post("/api/application/start")).andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void stateEndpointsRejectWrongMethod() throws Exception {
		mvc.perform(get("/api/application/stop")).andExpect(status().isMethodNotAllowed());
		mvc.perform(post("/api/application/state")).andExpect(status().isMethodNotAllowed());
	}

	@Test
	void strictCoverageRejectsLegacyAndUntrackedHistory() throws Exception {
		Instant legacyTime = Instant.parse("2000-01-01T00:00:01Z");
		try {
			repository.save(new UptimeRecord(legacyTime, true, 100, 100));
			for (String path : List.of("/api/uptime/at", "/api/uptime/event")) {
				mvc.perform(get(path).param("time", legacyTime.toString())).andExpect(status().is(422))
					.andExpect(jsonPath("$.code").value("OUTSIDE_TRACKING_COVERAGE"));
			}
			mvc.perform(get("/api/uptime").param("from", legacyTime.toString()).param("to", legacyTime.plusSeconds(120).toString()))
				.andExpect(status().is(422));
			// A prior stopped session can remain in the cached coordinator; explicit legacy bounds must still fail.
		} finally { repository.deleteById(legacyTime); }
	}

	@Test
	void explicitLifecycleAndLogicalHealthRemainIndependent() throws Exception {
		mvc.perform(post("/api/tracking/stop")).andExpect(status().isConflict());
		mvc.perform(post("/api/tracking/start")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"));
		var session = tracking.state().orElseThrow();
		ownedSession = session.id();
		mvc.perform(post("/api/tracking/start")).andExpect(status().isConflict());
		mvc.perform(post("/api/application/stop")).andExpect(status().isOk());
		now = now.plusMillis(10);
		clock.set(now);
		context.getBean(MonitoringScheduler.class).sample();
		mvc.perform(get("/api/tracking/state")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(get("/api/tracking/events").param("sessionId", session.id().toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].type").value("START"));
		now = now.plusMillis(10);
		clock.set(now);
		mvc.perform(post("/api/tracking/stop")).andExpect(status().isAccepted());
		Instant stop = tracking.state().orElseThrow().stoppedAt();
		context.getBean(MonitoringScheduler.class).flush();
		mvc.perform(get("/api/tracking/state")).andExpect(jsonPath("$.status").value("STOPPED"));
		mvc.perform(get("/api/tracking/events").param("sessionId", session.id().toString()))
			.andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].type").value("STOP"));
		mvc.perform(get("/api/uptime/at").param("time", stop.toString())).andExpect(status().is(422));
		Instant observed = saved.stream().flatMap(e -> e.badEvents().stream()).findFirst().orElseThrow().firstObservedAt();
		mvc.perform(get("/api/uptime/at").param("time", observed.toString())).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.down").value(true));
		mvc.perform(get("/api/uptime/event").param("time", observed.toString())).andExpect(status().isOk())
			.andExpect(jsonPath("$.badEvents[0].type").value("DOWNTIME"));
		mvc.perform(get("/api/uptime").param("from", session.startedAt().toString()).param("to", stop.minusNanos(1).toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].sessionId").value(session.id().toString()));
		mvc.perform(get("/api/uptime")).andExpect(status().isOk());
	}

	@Test
	void trackedMissingMeasurementsAreUnknownOrPendingAndRecordedSuccessIsUp() throws Exception {
		Instant start = now.minusSeconds(180);
		var session = new TrackingSession(java.util.UUID.randomUUID(), start, null, TrackingStatus.ACTIVE, null);
		memory.saveStart(session, new TrackingEvent(java.util.UUID.randomUUID(), session.id(), TrackingEventType.START, start, null));
		Instant healthy = start.plusSeconds(60);
		eventStore.saveAll(List.of(new com.example.uptime.aggregation.domain.UptimeEvent(java.util.UUID.randomUUID(), session.id(), healthy,
			healthy, healthy.plusSeconds(60), com.example.uptime.aggregation.domain.EventStatus.SUCCESS, 100, 100, false, List.of(), List.of())));
		mvc.perform(get("/api/uptime/at").param("time", start.plusMillis(500).toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"))
			.andExpect(jsonPath("$.down").value(org.hamcrest.Matchers.nullValue()));
		mvc.perform(get("/api/uptime/event").param("time", now.toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.badEvents.length()").value(0));
		mvc.perform(get("/api/uptime").param("from", start.toString()).param("to", start.plusSeconds(120).toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].status").value("UNKNOWN"))
			.andExpect(jsonPath("$[1].down").value(false))
			.andExpect(jsonPath("$[2].status").value("PENDING"));
		mvc.perform(get("/api/uptime")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void invalidRangesAreBadRequest() throws Exception {
		mvc.perform(get("/api/uptime").param("from", "2000-01-02T00:00:00Z").param("to", "2000-01-01T00:00:00Z"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("'from' must not be after 'to'"));
		mvc.perform(get("/api/uptime/at").param("time", "2999-01-01T00:00:00Z")).andExpect(status().isBadRequest());
	}

	@Test
	void malformedOrMissingParametersAreBadRequest() throws Exception {
		mvc.perform(get("/api/uptime/at")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/uptime/at").param("time", "yesterday")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/uptime").param("from", "not-a-date")).andExpect(status().isBadRequest());
	}

	@Test
	void swaggerUiAndApiDocsAreServed() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/uptime']").exists())
			.andExpect(jsonPath("$.paths['/api/uptime/at']").exists())
			.andExpect(jsonPath("$.paths['/api/application/stop']").exists())
			.andExpect(jsonPath("$.paths['/api/application/start']").exists())
			.andExpect(jsonPath("$.paths['/api/application/state']").exists());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

}
