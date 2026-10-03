package com.example.uptime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.example.uptime.aggregation.application.AggregateChecks;
import com.example.uptime.aggregation.application.UptimeEventStore;
import com.example.uptime.aggregation.application.UptimeHistoryEntry;
import com.example.uptime.aggregation.application.UptimeHistoryReader;
import com.example.uptime.aggregation.domain.BadEvent;
import com.example.uptime.aggregation.domain.BadEventType;
import com.example.uptime.aggregation.domain.EventStatus;
import com.example.uptime.aggregation.domain.FailureKey;
import com.example.uptime.aggregation.domain.TimeRange;
import com.example.uptime.aggregation.domain.UptimeEvent;
import com.example.uptime.aggregation.infrastructure.persistence.UptimeRecordRepository;
import com.example.uptime.checking.application.HealthProbe;
import com.example.uptime.checking.domain.FailureType;
import com.example.uptime.checking.domain.ProbeResult;
import com.example.uptime.state.ApplicationStateService;
import com.example.uptime.tracking.application.TrackingService;
import com.example.uptime.tracking.application.TrackingStore;
import com.example.uptime.tracking.domain.TrackingEvent;
import com.example.uptime.tracking.domain.TrackingEventType;
import com.example.uptime.tracking.domain.TrackingSession;
import com.example.uptime.tracking.domain.TrackingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/** Full production context and transaction proxies against migrated uptime_test; no scheduled or remote checks. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_PERSISTENCE_POSTGRES_TESTS", matches = "true")
@SpringBootTest(properties = {"uptime.scheduling-enabled=false", "spring.datasource.url=${UPTIME_TEST_DB_URL:jdbc:postgresql://localhost:5432/uptime_test}", "spring.datasource.username=${UPTIME_DB_USER:uptime}", "spring.datasource.password=${UPTIME_DB_PASSWORD:uptime}", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
class UptimeServiceApplicationPostgresTest {
	@Autowired MockMvc mvc;
	@Autowired ApplicationStateService applicationState;
	@Autowired AggregateChecks aggregation;
	@Autowired TrackingService tracking;
	@Autowired TrackingStore trackingStore;
	@Autowired UptimeEventStore eventStore;
	@Autowired UptimeHistoryReader history;
	@Autowired UptimeRecordRepository legacy;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactionManager;

	@MockitoBean Clock clock;
	@MockitoBean HealthProbe probe;

	// The cached application's tracking/aggregation state must never see the next test's clock move backwards.
	private static final Instant CLOCK_BASE = Instant.now().truncatedTo(ChronoUnit.MINUTES);
	private static final AtomicLong TEST_SEQUENCE = new AtomicLong();
	private final Set<UUID> ownedSessions = new HashSet<>();
	private final Set<UUID> directSessions = new HashSet<>();
	private final Set<Instant> ownedLegacyKeys = new HashSet<>();
	private Instant base;
	private Instant now;

	@BeforeEach
	void prepare() {
		base = CLOCK_BASE.plusSeconds(TEST_SEQUENCE.getAndIncrement() * 60);
		now = base;
		when(clock.instant()).thenAnswer(invocation -> now);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		when(probe.check()).thenReturn(ProbeResult.success());
		applicationState.start();
		assertThat(tracking.state().map(TrackingSession::status).orElse(TrackingStatus.STOPPED)).isEqualTo(TrackingStatus.STOPPED);
		assertThat(aggregation.hasUnfinalizedSession()).isFalse();
		assertThat(aggregation.status().pendingEvents()).isZero();
	}

	@AfterEach
	void reset() {
		try {
			var current = tracking.state();
			if (current.isPresent() && ownedSessions.contains(current.get().id())) {
				if (current.get().status() == TrackingStatus.ACTIVE) {
					advanceTo(now.plusNanos(1));
					tracking.stop();
				}
				for (int attempt = 0; attempt < 20 && tracking.state().orElseThrow().status() == TrackingStatus.STOPPING; attempt++) {
					advanceTo(now.plusSeconds(1));
					tracking.flush();
				}
				assertThat(tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
				assertThat(aggregation.status().pendingEvents()).isZero();
				assertThat(aggregation.hasUnfinalizedSession()).isFalse();
			}
			for (UUID id : directSessions) closeDirectSession(id);
		} finally {
			// Never truncate tables or delete somebody else's history. Children precede both FK parents.
			for (UUID id : ownedSessions) {
				jdbc.update("DELETE FROM bad_event WHERE session_id = ?", id);
				jdbc.update("DELETE FROM uptime_event WHERE session_id = ?", id);
				jdbc.update("DELETE FROM tracking_event WHERE session_id = ?", id);
				jdbc.update("DELETE FROM tracking_session WHERE id = ?", id);
			}
			for (Instant key : ownedLegacyKeys) jdbc.update("DELETE FROM uptime_record WHERE ts = ?", Timestamp.from(key));
			applicationState.start();
		}
	}

	@Test
	void startsStoppedAndSamplingSkipsUntilExplicitStart() {
		assertThat(AopUtils.isAopProxy(eventStore)).isTrue();
		assertThat(AopUtils.isAopProxy(trackingStore)).isTrue();
		assertThat(AopUtils.isAopProxy(history)).isTrue();
		assertThat(mockingDetails(transactionManager).isMock()).isFalse();
		tracking.sample();
		verify(probe, never()).check();
		assertThat(aggregation.status().openWindows()).isZero();
		assertThat(aggregation.status().pendingEvents()).isZero();
		TrackingSession session = startService(base.plusNanos(123456789));
		tracking.sample();
		verify(probe).check();
		assertThat(trackingStore.events(session.id())).singleElement().satisfies(event -> {
			assertThat(event.type()).isEqualTo(TrackingEventType.START);
			assertThat(event.occurredAt()).isEqualTo(session.startedAt());
		});
		stopAndFlush(session.startedAt().plusNanos(100));
	}

	@Test
	void trackingEndpointsPersistExplicitStartAndStopAfterRetainingExactIntent() throws Exception {
		Instant start = base.plusNanos(123456789);
		advanceTo(start);
		var startResponse = mvc.perform(post("/api/tracking/start"));
		TrackingSession session = tracking.state().orElseThrow();
		ownedSessions.add(session.id());
		startResponse.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.startedAt").value(start.toString()));
		tracking.sample();
		Instant cutoff = start.plusNanos(100);
		advanceTo(cutoff);
		mvc.perform(post("/api/tracking/stop")).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("STOPPING"))
				.andExpect(jsonPath("$.stoppedAt").value(cutoff.toString()));
		assertThat(tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.STOPPING);
		assertThat(trackingStore.find(session.id()).orElseThrow().status()).isEqualTo(TrackingStatus.ACTIVE);
		assertThat(trackingStore.events(session.id())).extracting(TrackingEvent::type).containsExactly(TrackingEventType.START);
		mvc.perform(get("/api/uptime/event").param("time", start.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
		outside(cutoff);
		tracking.flush();
		assertThat(tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(trackingStore.find(session.id()).orElseThrow().committedThrough()).isEqualTo(cutoff);
		assertThat(trackingStore.events(session.id())).extracting(TrackingEvent::type)
				.containsExactly(TrackingEventType.START, TrackingEventType.STOP);
		mvc.perform(get("/api/tracking/events").param("sessionId", session.id().toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].occurredAt").value(start.toString()))
				.andExpect(jsonPath("$[1].occurredAt").value(cutoff.toString()));
		tracking.sample();
		verify(probe, times(1)).check();
		mvc.perform(get("/api/uptime").param("from", start.toString()).param("to", start.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].status").value("SUCCESS"));
	}

	@Test
	void checkPipelineSeparatesDowntimeAndCheckFailureWithoutStoppingTracking() throws Exception {
		Instant start = base.plusNanos(123456789);
		TrackingSession session = startService(start);
		tracking.sample();
		Instant downAt = start.plusMillis(10).plusNanos(3);
		advanceTo(downAt);
		when(probe.check()).thenReturn(ProbeResult.failure("HEALTH_DOWN", "Remote health down", FailureType.DOWNTIME, "maintenance"));
		tracking.sample();
		assertThat(tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.ACTIVE);
		Instant timeoutAt = start.plusMillis(20).plusNanos(5);
		advanceTo(timeoutAt);
		when(probe.check()).thenReturn(ProbeResult.failure("HTTP_TIMEOUT", "Health request timed out", FailureType.CHECK_FAILURE, "timeout"));
		tracking.sample();
		assertThat(tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.ACTIVE);
		assertThat(trackingStore.events(session.id())).extracting(TrackingEvent::type).containsExactly(TrackingEventType.START);
		Instant healthyAt = start.plusMillis(30).plusNanos(7);
		advanceTo(healthyAt);
		when(probe.check()).thenReturn(ProbeResult.success());
		tracking.sample();
		Instant cutoff = start.plusMillis(40).plusNanos(9);
		stopAndFlush(cutoff);
		UptimeHistoryEntry entry = entry(session.id(), start);
		assertThat(entry.status()).isEqualTo(EventStatus.FAILED);
		assertThat(entry.totalChecks()).isEqualTo(4);
		assertThat(entry.successfulChecks()).isEqualTo(2);
		assertThat(entry.partialCoverage()).isTrue();
		assertThat(entry.unknownIntervals()).isEmpty();
		List<BadEvent> bad = history.badEvents(entry.eventId());
		assertThat(bad).extracting(BadEvent::type).containsExactly(BadEventType.DOWNTIME, BadEventType.CHECK_FAILURE);
		assertThat(bad.getFirst().windowStart()).isEqualTo(downAt);
		assertThat(bad.getFirst().windowEnd()).isEqualTo(timeoutAt);
		assertThat(bad.getFirst().firstObservedAt()).isEqualTo(downAt);
		assertThat(bad.getFirst().lastObservedAt()).isEqualTo(downAt);
		assertThat(bad.getLast().windowStart()).isEqualTo(timeoutAt);
		assertThat(bad.getLast().windowEnd()).isEqualTo(healthyAt);
		mvc.perform(get("/api/uptime/event").param("time", start.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failedChecks").value(2))
				.andExpect(jsonPath("$.badEvents[0].type").value("DOWNTIME"))
				.andExpect(jsonPath("$.badEvents[0].windowStart").value(downAt.toString()))
				.andExpect(jsonPath("$.badEvents[1].type").value("CHECK_FAILURE"))
				.andExpect(jsonPath("$.badEvents[1].windowStart").value(timeoutAt.toString()));
	}

	@Test
	void nanosecondStartStopEdgesAndSameSecondSessionsUseExactCoverage() throws Exception {
		Instant firstStart = base.plusNanos(123456789);
		TrackingSession first = startService(firstStart);
		tracking.sample();
		Instant firstEnd = firstStart.plusNanos(10);
		stopAndFlush(firstEnd);
		Instant secondStart = firstEnd.plusNanos(1);
		TrackingSession second = startService(secondStart);
		when(probe.check()).thenReturn(ProbeResult.failure("HEALTH_DOWN", "down", FailureType.DOWNTIME, null));
		tracking.sample();
		Instant secondEnd = secondStart.plusNanos(10);
		stopAndFlush(secondEnd);
		assertThat(entry(first.id(), firstStart).windowEnd()).isEqualTo(firstEnd);
		assertThat(entry(second.id(), secondStart).windowStart()).isEqualTo(secondStart);
		assertThat(history.range(firstStart, secondEnd.minusNanos(1))).extracting(UptimeHistoryEntry::sessionId)
				.contains(first.id(), second.id());
		assertThat(trackingStore.sessions(firstStart, firstStart)).extracting(TrackingSession::id).contains(first.id());
		assertThat(trackingStore.sessions(firstEnd, firstEnd)).isEmpty();
		mvc.perform(get("/api/uptime/at").param("time", firstStart.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(first.id().toString()))
				.andExpect(jsonPath("$.status").value("SUCCESS"));
		mvc.perform(get("/api/uptime/at").param("time", firstEnd.minusNanos(1).toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(first.id().toString()));
		mvc.perform(get("/api/uptime/at").param("time", secondStart.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(second.id().toString()))
				.andExpect(jsonPath("$.status").value("FAILED"));
		outside(firstStart.minusNanos(1));
		outside(firstEnd);
		outside(secondEnd);
		mvc.perform(get("/api/uptime").param("from", firstStart.toString()).param("to", secondEnd.minusNanos(1).toString()))
				.andExpect(status().is(422)).andExpect(jsonPath("$.code").value("OUTSIDE_TRACKING_COVERAGE"));
	}

	@Test
	void unobservedTrackedCoverageIsUnknownNotFabricatedDowntime() throws Exception {
		Instant start = base.plusNanos(123456789);
		TrackingSession session = startService(start);
		Instant cutoff = start.plusNanos(9);
		stopAndFlush(cutoff);
		UptimeHistoryEntry entry = entry(session.id(), start);
		assertThat(entry.status()).isEqualTo(EventStatus.UNKNOWN);
		assertThat(entry.unknownIntervals()).containsExactly(new TimeRange(start, cutoff));
		assertThat(entry.badEventIds()).isEmpty();
		mvc.perform(get("/api/uptime/at").param("time", start.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"))
				.andExpect(jsonPath("$.down").value(org.hamcrest.Matchers.nullValue()));
		mvc.perform(get("/api/uptime/event").param("time", start.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.unknownIntervals[0].start").value(start.toString()))
				.andExpect(jsonPath("$.unknownIntervals[0].end").value(cutoff.toString()));
	}

	@Test
	void productionUuidRetriesAndChildConflictsRollBackParentsChildrenAndProgress() {
		Instant start = base.plusNanos(123456789);
		TrackingSession session = startStore(start);
		UptimeEvent original = failed(session.id(), start, start.plusNanos(100));
		eventStore.saveAll(List.of(original, original));
		assertThat(parentCount(original.id())).isEqualTo(1);
		assertThat(childCount(original.id())).isEqualTo(2);
		assertThat(history.badEvents(original.id())).containsExactlyElementsOf(original.badEvents());
		assertThat(jdbc.queryForObject("SELECT jsonb_exists(payload, 'badEvents') OR jsonb_exists(payload, 'badEventIds') FROM uptime_event WHERE id = ?",
				Boolean.class, original.id())).isFalse();
		Instant laterStart = start.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
		UptimeEvent later = failed(session.id(), laterStart, laterStart.plusNanos(100));
		UptimeEvent changedParent = new UptimeEvent(original.id(), session.id(), original.bucketStart(), start, original.windowEnd(),
				EventStatus.FAILED, 3, 0, true, original.badEvents(), List.of());
		assertThatThrownBy(() -> eventStore.saveAll(List.of(later, changedParent))).isInstanceOf(IllegalStateException.class);
		assertThat(parentCount(later.id())).isZero();
		assertThat(childCount(later.id())).isZero();
		assertThat(trackingStore.find(session.id()).orElseThrow().committedThrough()).isEqualTo(original.windowEnd());
		BadEvent child = original.badEvents().getFirst();
		BadEvent changedChild = new BadEvent(child.id(), child.uptimeEventId(), child.sessionId(), child.type(), child.windowStart(),
				child.windowEnd(), child.firstObservedAt(), child.lastObservedAt(), child.observationCount(), child.failureKey(), "changed");
		assertThatThrownBy(() -> eventStore.saveAll(List.of(withChildren(original, List.of(changedChild, original.badEvents().getLast())))))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> eventStore.saveAll(List.of(withChildren(original, List.of(child)))))
				.isInstanceOf(IllegalStateException.class);
		BadEvent extra = new BadEvent(UUID.randomUUID(), original.id(), session.id(), child.type(), child.windowStart(), child.windowEnd(),
				child.firstObservedAt(), child.lastObservedAt(), 1, child.failureKey(), "extra");
		assertThatThrownBy(() -> eventStore.saveAll(List.of(withChildren(original, List.of(child, original.badEvents().getLast(), extra)))))
				.isInstanceOf(IllegalStateException.class);
		BadEvent reused = new BadEvent(child.id(), later.id(), session.id(), child.type(), later.windowStart(), later.windowEnd(),
				later.windowStart(), later.windowStart(), 1, child.failureKey(), "reused UUID");
		assertThatThrownBy(() -> eventStore.saveAll(List.of(withChildren(later, List.of(reused)))))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(parentCount(later.id())).isZero();
		assertThat(childCount(later.id())).isZero();
		assertThat(history.badEvents(original.id())).containsExactlyElementsOf(original.badEvents());
		assertThat(trackingStore.find(session.id()).orElseThrow().committedThrough()).isEqualTo(original.windowEnd());
		closeDirectSession(session.id());
	}

	@Test
	void productionCompleteStopRequiresCommittedCutoffAndPermitsEmptySession() {
		Instant start = base.plusNanos(123456789);
		TrackingSession session = startStore(start);
		Instant boundary = start.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
		Instant cutoff = boundary.plusNanos(9);
		TrackingSession stopping = new TrackingSession(session.id(), start, cutoff, TrackingStatus.STOPPING, null);
		trackingStore.saveStop(stopping, new TrackingEvent(UUID.randomUUID(), session.id(), TrackingEventType.STOP, cutoff, null));
		assertThatThrownBy(() -> trackingStore.completeStop(session.id())).isInstanceOf(IllegalStateException.class);
		assertThat(trackingStore.find(session.id()).orElseThrow().status()).isEqualTo(TrackingStatus.STOPPING);
		eventStore.saveAll(List.of(unknown(session.id(), start, boundary)));
		assertThatThrownBy(() -> trackingStore.completeStop(session.id())).isInstanceOf(IllegalStateException.class);
		eventStore.saveAll(List.of(unknown(session.id(), boundary, cutoff)));
		trackingStore.completeStop(session.id());
		trackingStore.completeStop(session.id());
		assertThat(trackingStore.find(session.id()).orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(trackingStore.find(session.id()).orElseThrow().committedThrough()).isEqualTo(cutoff);
		TrackingSession empty = startStore(cutoff);
		closeDirectSession(empty.id());
		assertThat(trackingStore.find(empty.id()).orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(trackingStore.find(empty.id()).orElseThrow().committedThrough()).isNull();
	}

	@Test
	void lifecycleConflictsRejectChangedParamsButStaleOriginalRetriesPreserveProgress() {
		Instant start = base.plusNanos(123456789);
		TrackingSession active = startStore(start);
		TrackingEvent begin = trackingStore.events(active.id()).getFirst();
		trackingStore.saveStart(active, begin);
		assertThatThrownBy(() -> trackingStore.saveStart(active,
				new TrackingEvent(begin.id(), active.id(), TrackingEventType.START, start, "changed")))
				.isInstanceOf(IllegalStateException.class);
		Instant cutoff = start.plusNanos(100);
		TrackingEvent stop = new TrackingEvent(UUID.randomUUID(), active.id(), TrackingEventType.STOP, cutoff, null);
		assertThatThrownBy(() -> trackingStore.saveStop(new TrackingSession(active.id(), start.plusNanos(1), cutoff, TrackingStatus.STOPPING, null), stop))
				.isInstanceOf(IllegalStateException.class);
		assertThat(trackingStore.find(active.id()).orElseThrow()).isEqualTo(active);
		assertThat(trackingStore.events(active.id())).containsExactly(begin);
		TrackingSession stopping = new TrackingSession(active.id(), start, cutoff, TrackingStatus.STOPPING, null);
		trackingStore.saveStop(stopping, stop);
		assertThatThrownBy(() -> trackingStore.saveStop(stopping,
				new TrackingEvent(stop.id(), active.id(), TrackingEventType.STOP, cutoff, "changed")))
				.isInstanceOf(IllegalStateException.class);
		eventStore.saveAll(List.of(unknown(active.id(), start, cutoff)));
		trackingStore.completeStop(active.id());
		trackingStore.saveStart(active, begin);
		trackingStore.saveStop(stopping, stop);
		assertThat(trackingStore.events(active.id())).containsExactly(begin, stop);
		assertThat(trackingStore.find(active.id()).orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(trackingStore.find(active.id()).orElseThrow().committedThrough()).isEqualTo(cutoff);
		UUID otherId = UUID.randomUUID();
		ownedSessions.add(otherId);
		TrackingSession other = new TrackingSession(otherId, cutoff, null, TrackingStatus.ACTIVE, null);
		assertThatThrownBy(() -> trackingStore.saveStart(other,
				new TrackingEvent(begin.id(), otherId, TrackingEventType.START, cutoff, null)))
				.isInstanceOf(IllegalStateException.class);
		assertThat(trackingStore.find(otherId)).isEmpty();
		assertThat(trackingStore.events(otherId)).isEmpty();
	}

	@Test
	void legacyRowRemainsReadableButIsNeverStrictTrackingHistory() throws Exception {
		Instant key = base;
		while (legacy.existsById(key)) key = key.plusSeconds(1);
		advanceTo(key.plusNanos(1));
		assertThat(trackingStore.sessions(key, key)).isEmpty();
		jdbc.update("INSERT INTO uptime_record (ts, up, samples, up_samples) VALUES (?, ?, ?, ?)", Timestamp.from(key), false, 100, 99);
		ownedLegacyKeys.add(key);
		assertThat(legacy.findById(key)).hasValueSatisfying(record -> {
			assertThat(record.getFailures()).isNull();
			assertThat(record.getPartialCoverage()).isNull();
		});
		assertThat(history.at(key)).isEmpty();
		outside(key);
	}

	@Test
	void applicationHealthControlsDoNotImplicitlyStartTracking() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(post("/api/application/stop")).andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(post("/api/application/start")).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		assertThat(tracking.state().map(TrackingSession::status).orElse(TrackingStatus.STOPPED)).isEqualTo(TrackingStatus.STOPPED);
		tracking.sample();
		verify(probe, never()).check();
	}

	@Test
	void invalidRangeAndFuturePointAreBadRequests() throws Exception {
		mvc.perform(get("/api/uptime").param("from", base.toString()).param("to", base.minusNanos(1).toString()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/uptime").param("from", base.minusSeconds(172800).toString()).param("to", base.toString()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/uptime/at").param("time", now.plusNanos(1).toString())).andExpect(status().isBadRequest());
	}

	@Test
	void swaggerUiAndApiDocsAreServed() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/uptime']").exists())
				.andExpect(jsonPath("$.paths['/api/tracking/start']").exists())
				.andExpect(jsonPath("$.paths['/api/application/stop']").exists());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	private TrackingSession startService(Instant start) {
		advanceTo(start);
		TrackingSession session = tracking.start();
		ownedSessions.add(session.id());
		assertThat(session.status()).isEqualTo(TrackingStatus.ACTIVE);
		assertThat(session.startedAt()).isEqualTo(start);
		return session;
	}

	private void stopAndFlush(Instant cutoff) {
		advanceTo(cutoff);
		TrackingSession stopping = tracking.stop();
		assertThat(stopping.status()).isEqualTo(TrackingStatus.STOPPING);
		assertThat(stopping.stoppedAt()).isEqualTo(cutoff);
		tracking.flush();
		assertThat(tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
		assertThat(aggregation.status().pendingEvents()).isZero();
	}

	private TrackingSession startStore(Instant start) {
		advanceTo(start);
		TrackingSession session = new TrackingSession(UUID.randomUUID(), start, null, TrackingStatus.ACTIVE, null);
		ownedSessions.add(session.id());
		directSessions.add(session.id());
		trackingStore.saveStart(session, new TrackingEvent(UUID.randomUUID(), session.id(), TrackingEventType.START, start, null));
		return session;
	}

	private void closeDirectSession(UUID id) {
		TrackingSession session = trackingStore.find(id).orElseThrow();
		if (session.status() == TrackingStatus.ACTIVE) {
			Instant cutoff = session.committedThrough() == null ? session.startedAt() : session.committedThrough();
			trackingStore.saveStop(new TrackingSession(id, session.startedAt(), cutoff, TrackingStatus.STOPPING, session.committedThrough()),
					new TrackingEvent(UUID.randomUUID(), id, TrackingEventType.STOP, cutoff, "TEST_CLEANUP"));
			session = trackingStore.find(id).orElseThrow();
		}
		if (session.status() == TrackingStatus.STOPPING && (session.stoppedAt().equals(session.startedAt())
				|| session.committedThrough() != null && !session.committedThrough().isBefore(session.stoppedAt()))) {
			trackingStore.completeStop(id);
		}
	}

	private void advanceTo(Instant time) {
		assertThat(time).as("Fixture clock must remain monotonic").isAfterOrEqualTo(now);
		now = time;
	}

	private UptimeHistoryEntry entry(UUID sessionId, Instant time) {
		return history.at(time).stream().filter(e -> e.sessionId().equals(sessionId)).findFirst().orElseThrow();
	}

	private void outside(Instant time) throws Exception {
		mvc.perform(get("/api/uptime/at").param("time", time.toString())).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("OUTSIDE_TRACKING_COVERAGE"));
	}

	private int parentCount(UUID id) {
		return jdbc.queryForObject("SELECT count(*) FROM uptime_event WHERE id = ?", Integer.class, id);
	}

	private int childCount(UUID id) {
		return jdbc.queryForObject("SELECT count(*) FROM bad_event WHERE uptime_event_id = ?", Integer.class, id);
	}

	private static UptimeEvent unknown(UUID sessionId, Instant start, Instant end) {
		return new UptimeEvent(UUID.randomUUID(), sessionId, start.truncatedTo(ChronoUnit.MINUTES), start, end,
				EventStatus.UNKNOWN, 0, 0, true, List.of(), List.of(new TimeRange(start, end)));
	}

	private static UptimeEvent failed(UUID sessionId, Instant start, Instant end) {
		UUID id = UUID.randomUUID();
		BadEvent down = new BadEvent(UUID.randomUUID(), id, sessionId, BadEventType.DOWNTIME, start.plusNanos(11), start.plusNanos(20),
				start.plusNanos(11), start.plusNanos(12), 1, new FailureKey(BadEventType.DOWNTIME, "HEALTH_DOWN", "maintenance"), "down");
		BadEvent checkFailure = new BadEvent(UUID.randomUUID(), id, sessionId, BadEventType.CHECK_FAILURE, start.plusNanos(30), start.plusNanos(40),
				start.plusNanos(30), start.plusNanos(31), 1, new FailureKey(BadEventType.CHECK_FAILURE, "HTTP_TIMEOUT", "timeout"), "timed out");
		return new UptimeEvent(id, sessionId, start.truncatedTo(ChronoUnit.MINUTES), start, end, EventStatus.FAILED,
				2, 0, true, List.of(down, checkFailure), List.of());
	}

	private static UptimeEvent withChildren(UptimeEvent event, List<BadEvent> children) {
		return new UptimeEvent(event.id(), event.sessionId(), event.bucketStart(), event.windowStart(), event.windowEnd(), event.status(),
				event.totalChecks(), event.successfulChecks(), event.partialCoverage(), children, event.unknownIntervals());
	}
}
