package com.example.uptime.aggregation.application;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import com.example.uptime.UptimeProperties;
import com.example.uptime.aggregation.domain.BadEvent;
import com.example.uptime.aggregation.domain.BadEventType;
import com.example.uptime.aggregation.domain.FailureKey;
import com.example.uptime.aggregation.domain.EventStatus;
import com.example.uptime.aggregation.domain.TimeRange;
import com.example.uptime.tracking.application.TrackingCoverage;
import com.example.uptime.tracking.domain.TrackingSession;
import com.example.uptime.tracking.domain.TrackingStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UptimeQueryServiceTest {
	private static final Instant START = Instant.parse("2026-10-03T12:00:00Z");
	private static final Instant NOW = START.plusSeconds((10) * 60);
	private UptimeHistoryReader reader;
	private TrackingCoverage coverage;
	private UptimeQueryService query;
	private TrackingSession session;

	@BeforeEach
	void setUp() {
		reader = mock(UptimeHistoryReader.class);
		coverage = mock(TrackingCoverage.class);
		session = session(START, null, START.plusSeconds((5) * 60));
		when(coverage.sessions(any(), any())).thenReturn(List.of(session));
		when(coverage.latest()).thenReturn(Optional.of(session));
		query = new UptimeQueryService(reader, coverage, new UptimeProperties(10000, 1000, 1200, 180),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void originalPointIsValidatedBeforeTruncation() {
		session = session(START.plusMillis((400) * 60), START.plusSeconds((2) * 60).plusMillis((300) * 60), START.plusSeconds((2) * 60));
		when(coverage.sessions(any(), any())).thenReturn(List.of(session));
		assertThrows(OutsideTrackingCoverageException.class, () -> query.at(START.plusMillis((399) * 60)));
		assertEquals("UNKNOWN", query.at(session.startedAt()).status());
		assertThrows(OutsideTrackingCoverageException.class, () -> query.at(session.stoppedAt()));
		assertThrows(OutsideTrackingCoverageException.class, () -> query.at(session.stoppedAt().plusNanos(1)));
	}

	@Test
	void futureOriginalTimestampIsRejectedEvenInCurrentSecond() {
		assertThrows(IllegalArgumentException.class, () -> query.at(NOW.plusNanos(1)));
		assertThrows(IllegalArgumentException.class, () -> query.range(START, NOW.plusNanos(1)));
		verifyNoInteractions(reader);
		assertEquals("PENDING", query.at(NOW).status());
	}

	@Test
	void firstGapFailsFullRangeBeforeReadingAndStopIsExclusive() {
		TrackingSession a = session(START, START.plusSeconds((2) * 60), START.plusSeconds((2) * 60));
		TrackingSession b = session(START.plusSeconds((3) * 60), START.plusSeconds((5) * 60), START.plusSeconds((5) * 60));
		when(coverage.sessions(any(), any())).thenReturn(List.of(b, a));
		OutsideTrackingCoverageException error = assertThrows(OutsideTrackingCoverageException.class,
				() -> query.range(START, START.plusSeconds((4) * 60)));
		assertEquals(START.plusSeconds((2) * 60), error.from());
		assertEquals(START.plusSeconds((3) * 60), error.to());
		assertThrows(OutsideTrackingCoverageException.class, () -> query.range(START, a.stoppedAt()));
		verifyNoInteractions(reader);
	}

	@Test
	void touchingSessionsInSameSecondRemainSeparateAndExactAtSelectsParent() {
		Instant split = START.plusMillis((500) * 60);
		TrackingSession a = session(START, split, split);
		TrackingSession b = session(split, START.plusSeconds((1) * 60), START.plusSeconds((1) * 60));
		when(coverage.sessions(any(), any())).thenReturn(List.of(a, b));
		UptimeHistoryEntry first = entry(a, START, split, EventStatus.FAILED);
		UptimeHistoryEntry second = entry(b, split, START.plusSeconds((1) * 60), EventStatus.SUCCESS);
		when(reader.range(START, START.plusMillis((999) * 60))).thenReturn(List.of(second, first));
		List<UptimePoint> points = query.range(START, START.plusMillis((999) * 60));
		assertEquals(2, points.size());
		assertEquals(points.get(0).time(), points.get(1).time());
		assertEquals(a.id(), points.get(0).sessionId());
		assertTrue(points.get(0).down());
		assertFalse(points.get(1).down());
		when(reader.at(split)).thenReturn(List.of(first, second));
		assertEquals(b.id(), query.at(split).sessionId());
		verify(reader).at(split);
	}

	@Test
	void missingMeasurementsAreUnknownOrPendingNeverDown() {
		UptimeEventDetails unknown = query.event(START);
		assertEquals("UNKNOWN", unknown.status());
		assertNull(unknown.eventId());
		assertEquals(0, unknown.totalChecks());
		assertEquals(List.of(new TimeRange(START, START.plusSeconds((1) * 60))), unknown.unknownIntervals());
		assertNull(query.at(START).down());
		UptimeEventDetails pending = query.event(START.plusSeconds((6) * 60));
		assertEquals("PENDING", pending.status());
		assertTrue(pending.badEvents().isEmpty());
		assertNull(query.at(START.plusSeconds((6) * 60)).down());
		UptimeHistoryEntry recorded = entry(session, START, START.plusSeconds((1) * 60), EventStatus.UNKNOWN);
		when(reader.at(START)).thenReturn(List.of(recorded));
		assertEquals("UNKNOWN", query.at(START).status());
		assertNull(query.at(START).down());
	}

	@Test
	void defaultRangeClipsToLatestCommittedAndStoppedExclusiveEnd() {
		session = session(START.plusMillis((200) * 60), START.plusSeconds((4) * 60), START.plusSeconds((4) * 60));
		when(coverage.latest()).thenReturn(Optional.of(session));
		when(coverage.sessions(any(), any())).thenReturn(List.of(session));
		assertEquals(3, query.range(null, null).size());
		verify(reader).range(START.plusSeconds((1) * 60), START.plusSeconds((4) * 60).minusNanos(1));
	}

	@Test
	void explicitBoundsAreNeverClampedAndNoDataIsMeaningful() {
		assertThrows(OutsideTrackingCoverageException.class, () -> query.range(START.minusNanos(1), null));
		assertThrows(OutsideTrackingCoverageException.class, () -> query.range(null, START.minusNanos(1)));
		when(coverage.latest()).thenReturn(Optional.empty());
		assertTrue(assertThrows(IllegalArgumentException.class, () -> query.range(null, null))
				.getMessage().contains("No tracking history"));
		when(coverage.latest()).thenReturn(Optional.of(session(START, null, null)));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> query.range(null, null))
				.getMessage().contains("No committed"));
	}

	@Test
	void defaultStartForAnExplicitHistoricalEndUsesThatSessionNotTheLatest() {
		TrackingSession historical = session(START, START.plusSeconds((2) * 60), START.plusSeconds((2) * 60));
		TrackingSession latest = session(START.plusSeconds((5) * 60), null, START.plusSeconds((8) * 60));
		when(coverage.latest()).thenReturn(Optional.of(latest));
		when(coverage.sessions(any(), any())).thenReturn(List.of(historical, latest));
		assertEquals(2, query.range(null, START.plusMillis((1500) * 60)).size());
		verify(reader).range(START, START.plusMillis((1500) * 60));
	}

	@Test
	void inclusiveBoundsRetainPartialWindowsAndValidateRangeLimits() {
		assertEquals(3, query.range(START.plusMillis((100) * 60), START.plusSeconds((2) * 60).plusMillis((900) * 60)).size());
		verify(reader).range(START.plusMillis((100) * 60), START.plusSeconds((2) * 60).plusMillis((900) * 60));
		assertThrows(IllegalArgumentException.class, () -> query.range(START.plusSeconds((1) * 60), START));
		UptimeQueryService limited = new UptimeQueryService(reader, coverage, new UptimeProperties(10000, 1000, 120, 120),
				Clock.fixed(NOW, ZoneOffset.UTC));
		assertThrows(IllegalArgumentException.class, () -> limited.range(START, START.plusSeconds((2) * 60)));
	}

	@Test
	void dataAccessErrorsPropagateWithoutDowntimeInference() {
		DataAccessResourceFailureException error = new DataAccessResourceFailureException("secret");
		when(reader.at(START)).thenThrow(error);
		when(reader.range(any(), any())).thenThrow(error);
		assertSame(error, assertThrows(DataAccessResourceFailureException.class, () -> query.at(START)));
		assertSame(error, assertThrows(DataAccessResourceFailureException.class, () -> query.event(START)));
		assertSame(error, assertThrows(DataAccessResourceFailureException.class, () -> query.range(null, null)));
	}

	@Test
	void detailsLoadChildrenByParentIdAndCopyProjectionLists() {
		UUID eventId = UUID.randomUUID();
		UUID childId = UUID.randomUUID();
		List<UUID> ids = new ArrayList<>(List.of(childId));
		List<TimeRange> unknown = new ArrayList<>(List.of(new TimeRange(START, START.plusMillis((100) * 60))));
		UptimeHistoryEntry entry = new UptimeHistoryEntry(eventId, session.id(), START, START,
				START.plusSeconds((1) * 60), EventStatus.FAILED, 3, 2, true, ids, unknown);
		ids.clear();
		unknown.clear();
		BadEvent child = new BadEvent(
				childId, eventId, session.id(), BadEventType.CHECK_FAILURE,
				START, START.plusSeconds((1) * 60), START.plusNanos(123), START.plusNanos(123), 1,
				new FailureKey(BadEventType.CHECK_FAILURE, "PROBE_EXCEPTION", "Probe failed"),
				"Probe failed");
		when(reader.at(START)).thenReturn(List.of(entry));
		when(reader.badEvents(eventId)).thenReturn(List.of(child));
		UptimeEventDetails details = query.event(START);
		assertEquals(eventId, details.eventId());
		assertEquals(List.of(childId), details.badEventIds());
		assertEquals(List.of(child), details.badEvents());
		assertEquals(1, details.failedChecks());
		assertEquals(1, details.unknownIntervals().size());
		assertThrows(UnsupportedOperationException.class, () -> details.badEvents().clear());
		assertThrows(UnsupportedOperationException.class, () -> entry.badEventIds().clear());
		verify(reader).badEvents(eventId);
	}

	@Test
	void rangeAndPointUseMinuteBucketsWithExactInclusiveAndExclusiveEdges() {
		var minute = entry(session, START, START.plusSeconds(60), EventStatus.SUCCESS);
		when(reader.range(any(), any())).thenReturn(List.of(minute));
		assertEquals(1, query.range(START.plusSeconds(10), START.plusSeconds(60).minusNanos(1)).size());
		var points = query.range(START.plusSeconds(10), START.plusSeconds(60));
		assertEquals(2, points.size());
		assertEquals(START, points.getFirst().time());
		assertEquals(START.plusSeconds(60), points.getLast().time());
		when(reader.at(START.plusSeconds(59))).thenReturn(List.of(minute));
		assertEquals(minute.eventId(), query.event(START.plusSeconds(59)).eventId());
		assertEquals(START.plusSeconds(60), query.event(START.plusSeconds(60)).bucketStart());
	}

	@Test
	void historicalSecondParentsRemainReadableWithinMinuteQuery() {
		var oldBucket = START.plusSeconds(15);
		var historical = new UptimeHistoryEntry(UUID.randomUUID(), session.id(), oldBucket, oldBucket,
				oldBucket.plusSeconds(1), EventStatus.SUCCESS, 100, 100, false, List.of(), List.of());
		when(reader.range(oldBucket, oldBucket)).thenReturn(List.of(historical));
		assertEquals(oldBucket, query.range(oldBucket, oldBucket).getFirst().time());
	}

	private static TrackingSession session(Instant start, Instant stop, Instant committed) {
		return new TrackingSession(UUID.randomUUID(), start, stop,
				stop == null ? TrackingStatus.ACTIVE : TrackingStatus.STOPPED, committed);
	}

	private static UptimeHistoryEntry entry(TrackingSession session, Instant start, Instant end, EventStatus status) {
		return new UptimeHistoryEntry(UUID.randomUUID(), session.id(), START, start, end, status,
				status == EventStatus.UNKNOWN ? 0 : 2, status == EventStatus.SUCCESS ? 2 : 0,
				true, List.of(), status == EventStatus.UNKNOWN ? List.of(new TimeRange(start, end)) : List.of());
	}
}
