package com.example.uptime.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import com.example.uptime.aggregation.domain.EventStatus;
import com.example.uptime.checking.domain.FailureType;
import com.example.uptime.checking.domain.ProbeResult;
import com.example.uptime.tracking.application.TrackingFixture;
import com.example.uptime.tracking.domain.TrackingEvent;
import com.example.uptime.tracking.domain.TrackingEventType;
import com.example.uptime.tracking.domain.TrackingStatus;

/** Regression coverage for the sampler, now delegated through the explicit tracking lifecycle. */
class UptimeSamplerTest {
 private final TrackingFixture fixture = new TrackingFixture();
 private final MonitoringScheduler scheduler = new MonitoringScheduler(fixture.tracking, fixture.aggregation);

 @Test void flushWithoutTrackingOrSamplesWritesNothing() {
  scheduler.sample(); scheduler.flush();
  verifyNoInteractions(fixture.probe, fixture.store);
 }
 @Test void currentSecondIsNotFlushedWhileStillFilling() {
  fixture.tracking.start(); sampleAt(100); fixture.at((900) * 60); scheduler.flush();
  verify(fixture.store, never()).saveAll(anyList());
 }
 @Test void completedUpSecondIsPersistedWithCountsAndUnknownGaps() {
  fixture.tracking.start(); sampleAt(10); sampleAt(20); sampleAt(990); flushAt(1000);
  assertThat(fixture.saved).singleElement().satisfies(e -> {
   assertThat(e.bucketStart()).isEqualTo(TrackingFixture.START);
   assertThat(e.totalChecks()).isEqualTo(3);
   assertThat(e.successfulChecks()).isEqualTo(3);
   assertThat(e.status()).isEqualTo(EventStatus.UNKNOWN);
   assertThat(e.unknownIntervals()).isNotEmpty();
  });
 }
 @Test void secondWithAnyFailureIsFailedButTrackingContinues() {
  var session = fixture.tracking.start(); sampleAt(0);
  when(fixture.probe.check()).thenReturn(ProbeResult.failure("HEALTH_DOWN", "Down", FailureType.DOWNTIME, "maintenance"));
  sampleAt(10);
  when(fixture.probe.check()).thenReturn(ProbeResult.success()); sampleAt(20); flushAt(1000);
  assertThat(fixture.saved).singleElement().satisfies(e -> {
   assertThat(e.status()).isEqualTo(EventStatus.FAILED);
   assertThat(e.totalChecks()).isEqualTo(3); assertThat(e.successfulChecks()).isEqualTo(2);
   assertThat(e.badEvents()).hasSize(1);
  });
  assertThat(fixture.tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.ACTIVE);
  assertThat(fixture.tracking.events(session.id())).extracting(TrackingEvent::type).containsExactly(TrackingEventType.START);
 }
 @Test void fullyDownSecondHasNoSuccessfulChecks() {
  fixture.tracking.start(); when(fixture.probe.check()).thenReturn(ProbeResult.failure("HTTP_TIMEOUT", "Timed out"));
  sampleAt(10); sampleAt(20); flushAt(1000);
  assertThat(fixture.saved).singleElement().satisfies(e -> {
   assertThat(e.status()).isEqualTo(EventStatus.FAILED); assertThat(e.successfulChecks()).isZero();
  });
 }
 @Test void completedSecondsFlushInOrderAndCurrentOneIsRetained() {
  fixture.tracking.start(); sampleAt(500); sampleAt(1000); sampleAt(2000); sampleAt(3000); flushAt(3001);
  assertThat(fixture.saved).extracting(e -> e.bucketStart()).containsExactly(TrackingFixture.START, TrackingFixture.START.plusSeconds((1) * 60), TrackingFixture.START.plusSeconds((2) * 60));
  assertThat(fixture.aggregation.status().openWindows()).isEqualTo(1);
  flushAt(4000); assertThat(fixture.saved).hasSize(4);
 }
 @Test void flushedSecondsAreNotWrittenTwice() {
  fixture.tracking.start(); sampleAt(0); flushAt(1000); scheduler.flush();
  verify(fixture.store, times(1)).saveAll(anyList());
 }
 @Test void explicitStopDisablesSamplesAndFlushesPartialTail() {
  fixture.tracking.start(); sampleAt(0); fixture.at((20) * 60); fixture.tracking.stop(); scheduler.sample(); scheduler.flush();
  verify(fixture.probe, times(1)).check();
  assertThat(fixture.tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.STOPPED);
  assertThat(fixture.saved).singleElement().satisfies(e -> assertThat(e.windowEnd()).isEqualTo(TrackingFixture.START.plusMillis((20) * 60)));
 }
 private void sampleAt(long millis) { fixture.at((millis) * 60); scheduler.sample(); }
 private void flushAt(long millis) { fixture.at((millis) * 60); scheduler.flush(); }
}
