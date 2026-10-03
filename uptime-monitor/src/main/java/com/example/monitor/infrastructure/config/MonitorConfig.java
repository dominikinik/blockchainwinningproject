package com.example.monitor.infrastructure.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.example.monitor.application.TrackingService;
import com.example.monitor.domain.DowntimePublisher;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.domain.TrackingEventStore;
import com.example.monitor.infrastructure.persistence.InMemoryTrackingEventStore;
import com.example.monitor.infrastructure.probe.HttpHealthProbe;
import com.example.monitor.infrastructure.solana.HttpSolanaRpc;
import com.example.monitor.infrastructure.solana.LoggingDowntimePublisher;
import com.example.monitor.infrastructure.solana.OracleKey;
import com.example.monitor.infrastructure.solana.SolanaMemoDowntimePublisher;
import com.example.monitor.infrastructure.solana.SolanaRpc;

/** Wires the framework-free domain and application layers to their adapters. */
@Configuration
public class MonitorConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	TrackingEventStore trackingEventStore() {
		return new InMemoryTrackingEventStore();
	}

	@Bean
	HealthProbe healthProbe(MonitorProperties properties) {
		return new HttpHealthProbe(RestClient.builder()
			.requestFactory(HttpTimeouts.timeouts(Duration.ofMillis(properties.probeTimeoutMs()))));
	}

	@Bean
	TrackingService trackingService(TrackingEventStore store, HealthProbe probe, DowntimePublisher publisher,
			Clock clock, MonitorProperties properties) {
		return new TrackingService(store, probe, publisher, clock, Duration.ofMillis(properties.checkIntervalMs()));
	}

	@Bean
	@ConditionalOnProperty(name = "monitor.blockchain.enabled", havingValue = "false")
	DowntimePublisher loggingDowntimePublisher() {
		return new LoggingDowntimePublisher();
	}

	@Configuration
	@ConditionalOnProperty(name = "monitor.blockchain.enabled", havingValue = "true", matchIfMissing = true)
	static class Blockchain {

		@Bean
		OracleKey oracleKey(MonitorProperties properties) throws IOException {
			String path = properties.blockchain().oracleKeypair();
			return path == null || path.isBlank() ? OracleKey.generate() : OracleKey.loadOrCreate(Path.of(path));
		}

		@Bean
		SolanaRpc solanaRpc(MonitorProperties properties) {
			return new HttpSolanaRpc(RestClient.builder()
				.baseUrl(properties.blockchain().rpcUrl())
				.requestFactory(HttpTimeouts.timeouts(Duration.ofMillis(properties.blockchain().rpcTimeoutMs()))));
		}

		@Bean
		DowntimePublisher solanaDowntimePublisher(SolanaRpc rpc, OracleKey oracle, MonitorProperties properties) {
			return new SolanaMemoDowntimePublisher(rpc, oracle, properties.blockchain().oracleMinLamports(),
					properties.blockchain().oracleAirdropLamports());
		}

	}

}
