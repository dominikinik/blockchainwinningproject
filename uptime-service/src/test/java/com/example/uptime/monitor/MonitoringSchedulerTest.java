package com.example.uptime.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import com.example.uptime.tracking.application.TrackingFixture;
import com.example.uptime.tracking.domain.TrackingStatus;

class MonitoringSchedulerTest {
	@Test
	void schedulerDoesNotCheckUntilExplicitStart() {
		TrackingFixture f = new TrackingFixture();
		MonitoringScheduler scheduler = new MonitoringScheduler(f.tracking, f.aggregation);
		scheduler.sample(); verifyNoInteractions(f.probe);
		f.tracking.start(); scheduler.sample(); verify(f.probe).check();
	}

	@Test
	void rejectedChecksRemainVisibleAndNeverStopTracking() {
		TrackingFixture f = new TrackingFixture(1);
		MonitoringScheduler scheduler = new MonitoringScheduler(f.tracking, f.aggregation);
		AggregationHealthIndicator health = new AggregationHealthIndicator(f.aggregation, f.tracking);
		f.tracking.start(); scheduler.sample(); f.at((1000) * 60); scheduler.sample();
		assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
		assertThat(health.health().getDetails().get("capacityRejections")).isEqualTo(1L);
		assertThat(f.tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.ACTIVE);
		scheduler.flush(); scheduler.sample();
		assertThat(f.aggregation.status().openWindows()).isEqualTo(1);
		assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
	}

	@Test
	void failedPersistenceIsRetainedAndSuccessfulRetryRestoresHealth() {
		TrackingFixture f = new TrackingFixture();
		MonitoringScheduler scheduler = new MonitoringScheduler(f.tracking, f.aggregation);
		AggregationHealthIndicator health = new AggregationHealthIndicator(f.aggregation, f.tracking);
		doThrow(new IllegalStateException("secret detail")).doAnswer(call -> { f.commit(call.getArgument(0)); return null; })
				.when(f.store).saveAll(anyList());
		f.tracking.start(); scheduler.sample(); f.at((1000) * 60); scheduler.flush();
		assertThat(f.aggregation.status().pendingEvents()).isEqualTo(1);
		assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
		assertThat(f.tracking.state().orElseThrow().status()).isEqualTo(TrackingStatus.ACTIVE);
		f.at((1100) * 60); scheduler.flush();
		assertThat(f.aggregation.status().pendingEvents()).isZero();
		assertThat(health.health().getStatus()).isEqualTo(Status.UP);
		assertThat(health.health().getDetails()).doesNotContainKeys("lifecycleFailure", "lastPersistenceFailure");
	}
}
