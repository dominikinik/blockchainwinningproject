package com.example.uptime.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.example.uptime.UptimeProperties;
import com.example.uptime.aggregation.application.AggregateChecks;
import com.example.uptime.aggregation.application.UptimeEventStore;
import com.example.uptime.checking.application.ExecuteCheck;
import com.example.uptime.state.ApplicationStateHealthIndicator;
import com.example.uptime.state.ApplicationStateService;
import com.example.uptime.tracking.application.TrackingService;
import com.example.uptime.tracking.application.TrackingFixture.MemoryTrackingStore;
import com.example.uptime.tracking.application.TrackingStore;
import com.example.uptime.checking.infrastructure.HttpHealthProbe;
import com.example.uptime.checking.application.HealthProbe;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MonitoringConfigurationTest {
	private final UptimeEventStore store = mock(UptimeEventStore.class);
	private final Instant now = Instant.parse("2026-10-03T12:00:00.123456789Z");
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class, MonitoringConfiguration.class,
					MonitoringScheduler.class, AggregationHealthIndicator.class)
			.withBean(Clock.class, () -> Clock.fixed(now, ZoneOffset.UTC))
			.withBean(UptimeEventStore.class, () -> store)
						.withBean(TrackingStore.class, MemoryTrackingStore::new)
						.withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
			.withBean(ApplicationStateHealthIndicator.class,
					() -> new ApplicationStateHealthIndicator(new ApplicationStateService()))
			.withPropertyValues("uptime.sample-interval-ms=10", "uptime.flush-interval-ms=1000",
					"uptime.max-range-seconds=86400", "uptime.default-range-seconds=300");

	@Test
	void bindsDefaultsAndWiresBothModulesWithoutDatabase() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(MonitoringScheduler.class)
					.hasSingleBean(AggregationHealthIndicator.class);
			UptimeProperties properties = context.getBean(UptimeProperties.class);
			assertThat(properties.maxBufferedWindows()).isEqualTo(3600);
			assertThat(properties.persistenceBatchSize()).isEqualTo(60);
			assertThat(properties.retryInitialMs()).isEqualTo(1000);
			assertThat(properties.retryMaxMs()).isEqualTo(30000);
			assertThat(properties.maxObservationGapMs()).isEqualTo(50);
			context.getBean(TrackingService.class).start();
			context.getBean(ExecuteCheck.class).execute();
			AggregateChecks aggregation = context.getBean(AggregateChecks.class);
			aggregation.completeBefore(now.plusSeconds(1));
			aggregation.persistPending();
			verify(store).saveAll(anyList());
			assertThat(aggregation.status().pendingEvents()).isZero();
		});
	}

	@Test
	void bindsOverridesAndAllowsSchedulingToBeDisabled() {
		runner.withPropertyValues("uptime.scheduling-enabled=false", "uptime.max-buffered-windows=120",
				"uptime.persistence-batch-size=20", "uptime.retry-initial-ms=50", "uptime.retry-max-ms=500")
				.run(context -> {
					assertThat(context).hasNotFailed().doesNotHaveBean(MonitoringScheduler.class);
					UptimeProperties properties = context.getBean(UptimeProperties.class);
					assertThat(properties.maxBufferedWindows()).isEqualTo(120);
					assertThat(properties.persistenceBatchSize()).isEqualTo(20);
					assertThat(properties.retryInitialMs()).isEqualTo(50);
					assertThat(properties.retryMaxMs()).isEqualTo(500);
				});
	}

	@Test
	void configuredUrlSelectsHttpProbeWithoutMakingNetworkCalls() {
		runner.withPropertyValues("uptime.probe.url=https://example.invalid/health",
				"uptime.probe.timeout-ms=250", "uptime.probe.healthy-value=healthy",
				"uptime.probe.unhealthy-value=unhealthy").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(HealthProbe.class)).isInstanceOf(HttpHealthProbe.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(UptimeProperties.class)
	static class PropertiesConfiguration {
	}
}
