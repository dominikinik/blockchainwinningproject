package com.example.uptime.monitor;

import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.uptime.UptimeProperties;
import com.example.uptime.aggregation.application.AggregateChecks;
import com.example.uptime.aggregation.application.UptimeEventStore;
import com.example.uptime.checking.application.CheckResultSink;
import com.example.uptime.checking.application.ExecuteCheck;
import com.example.uptime.checking.application.HealthProbe;
import com.example.uptime.checking.infrastructure.ApplicationStateHealthProbe;
import com.example.uptime.checking.infrastructure.HttpHealthProbe;
import com.example.uptime.checking.infrastructure.HttpProbeProperties;
import com.example.uptime.state.ApplicationStateHealthIndicator;
import com.example.uptime.tracking.application.TrackingService;
import com.example.uptime.tracking.application.TrackingStore;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HttpProbeProperties.class)
public class MonitoringConfiguration {

	@Bean
	HttpClient healthHttpClient(HttpProbeProperties properties) {
		return HttpClient.newBuilder().connectTimeout(properties.timeout())
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	@Bean
	HealthProbe healthProbe(HttpClient healthHttpClient, HttpProbeProperties properties,
			ApplicationStateHealthIndicator indicator, ObjectMapper mapper) {
		return properties.url() == null ? new ApplicationStateHealthProbe(indicator)
				: new HttpHealthProbe(healthHttpClient, properties, mapper);
	}

	@Bean
	ExecuteCheck executeCheck(HealthProbe probe, CheckResultSink sink, Clock clock) {
		return new ExecuteCheck(probe, sink, clock);
	}

	@Bean
	AggregateChecks aggregateChecks(UptimeEventStore store, Clock clock, UptimeProperties properties) {
		int expectedChecks = (int) Math.ceil(1000.0 / properties.sampleIntervalMs());
		return new AggregateChecks(store, clock, expectedChecks, properties.maxBufferedWindows(),
				properties.persistenceBatchSize(), properties.retryInitialMs(), properties.retryMaxMs(),
				properties.maxObservationGapMs());
	}

	@Bean
	TrackingService trackingService(AggregateChecks aggregation, ExecuteCheck checks, TrackingStore store, Clock clock) {
		return new TrackingService(aggregation, checks, store, clock);
	}
}
