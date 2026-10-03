package com.example.uptime.uptime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.example.uptime.UptimeProperties;
import com.example.uptime.aggregation.application.*;
import com.example.uptime.aggregation.domain.EventStatus;
import com.example.uptime.support.MutableClock;
import com.example.uptime.tracking.application.TrackingCoverage;
import com.example.uptime.tracking.domain.*;

/** Portable upstream query regressions adapted to session-scoped history, not legacy rows. */
class UptimeQueryServiceTest {
 private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
 private final UptimeHistoryReader reader = mock(UptimeHistoryReader.class);
 private final TrackingCoverage coverage = mock(TrackingCoverage.class);
 private final MutableClock clock = new MutableClock(T0.plusSeconds((120) * 60));
 private final TrackingSession session = new TrackingSession(UUID.randomUUID(), T0, null, TrackingStatus.ACTIVE, T0.plusSeconds((110) * 60));
 private final UptimeQueryService service = new UptimeQueryService(reader, coverage, new UptimeProperties(10000, 1000, 3600, 300), clock);
 @BeforeEach void setUp() {
  when(coverage.sessions(any(), any())).thenReturn(List.of(session));
  when(coverage.latest()).thenReturn(Optional.of(session));
 }
 @Test void atValidatesOriginalTimestampAndMissingDataIsUnknown() {
  assertThat(service.at(T0.plusMillis((999) * 60)).status()).isEqualTo("UNKNOWN");
  assertThat(service.at(T0.plusMillis((999) * 60)).down()).isNull();
  verify(reader, times(2)).at(T0.plusMillis((999) * 60));
 }
 @Test void atReflectsRecordedState() {
  when(reader.at(T0)).thenReturn(List.of(entry(T0, EventStatus.SUCCESS)));
  when(reader.at(T0.plusSeconds((1) * 60))).thenReturn(List.of(entry(T0.plusSeconds((1) * 60), EventStatus.FAILED)));
  assertThat(service.at(T0).down()).isFalse(); assertThat(service.at(T0.plusSeconds((1) * 60)).down()).isTrue();
 }
 @Test void rangeReturnsInclusiveSecondsWithUnknownGaps() {
  when(reader.range(T0, T0.plusSeconds((3) * 60))).thenReturn(List.of(entry(T0.plusSeconds((1) * 60), EventStatus.SUCCESS), entry(T0.plusSeconds((2) * 60), EventStatus.FAILED)));
  var points = service.range(T0, T0.plusSeconds((3) * 60));
  assertThat(points).extracting(UptimePoint::status).containsExactly("UNKNOWN", "SUCCESS", "FAILED", "UNKNOWN");
  assertThat(points.getFirst().down()).isNull();
 }
 @Test void rangePreservesOriginalBoundsForHistorySelection() {
  assertThat(service.range(T0.plusMillis((900) * 60), T0.plusSeconds((1) * 60).plusMillis((100) * 60))).extracting(UptimePoint::time).containsExactly(T0, T0.plusSeconds((1) * 60));
  verify(reader).range(T0.plusMillis((900) * 60), T0.plusSeconds((1) * 60).plusMillis((100) * 60));
 }
 @Test void singleSecondRange() { assertThat(service.range(T0, T0)).hasSize(1); }
 @Test void defaultRangeEndsAtCommittedFrontierNotNow() {
  assertThat(service.range(null, null)).extracting(UptimePoint::time).containsExactly(T0.plusSeconds((105) * 60), T0.plusSeconds((106) * 60), T0.plusSeconds((107) * 60), T0.plusSeconds((108) * 60), T0.plusSeconds((109) * 60));
 }
 @Test void onlyToUsesDefaultLengthWithinSession() {
  assertThat(service.range(null, T0.plusSeconds((100) * 60))).extracting(UptimePoint::time).first().isEqualTo(T0.plusSeconds((95) * 60));
 }
 @Test void onlyFromEndsAtCommittedFrontier() {
  assertThat(service.range(T0.plusSeconds((108) * 60), null)).extracting(UptimePoint::time).containsExactly(T0.plusSeconds((108) * 60), T0.plusSeconds((109) * 60));
 }
 @Test void reversedRangeIsRejected() {
  assertThatIllegalArgumentException().isThrownBy(() -> service.range(T0.plusSeconds((1) * 60), T0)).withMessage("'from' must not be after 'to'");
 }
 @Test void rangeAtMaximumIsAllowed() { assertThat(service.range(T0, T0.plusSeconds((59) * 60))).hasSize(60); }
 @Test void rangeOverMaximumIsRejected() {
  assertThatIllegalArgumentException().isThrownBy(() -> service.range(T0, T0.plusSeconds((60) * 60))).withMessage("Range exceeds maximum of 3600s");
 }
 @Test void outsideTrackingIsRejectedBeforeReadingHistory() {
  when(coverage.sessions(any(), any())).thenReturn(List.of());
  assertThatThrownBy(() -> service.at(T0)).isInstanceOf(OutsideTrackingCoverageException.class);
  verifyNoInteractions(reader);
 }
 @Test void futureAndAbsentDefaultHistoryAreRejected() {
  assertThatIllegalArgumentException().isThrownBy(() -> service.at(clock.instant().plusNanos(1)));
  when(coverage.latest()).thenReturn(Optional.empty());
  assertThatIllegalArgumentException().isThrownBy(() -> service.range(null, null));
  verifyNoInteractions(reader);
 }
 private UptimeHistoryEntry entry(Instant bucket, EventStatus status) {
  return new UptimeHistoryEntry(UUID.randomUUID(), session.id(), bucket, bucket, bucket.plusSeconds((1) * 60), status, 100, status == EventStatus.SUCCESS ? 100 : 0, false, List.of(), List.of());
 }
}
