package com.example.monitor.infrastructure.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.example.monitor.application.DealService;
import com.example.monitor.application.TrackingEventListener;
import com.example.monitor.application.TrackingService;
import com.example.monitor.application.UptimeHistory;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.DowntimePublisher;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.domain.TrackingEventStore;
import com.example.monitor.domain.deal.DealChain;
import com.example.monitor.domain.deal.UptimeDealRepository;
import com.example.monitor.infrastructure.persistence.JdbcTrackingEventStore;
import com.example.monitor.infrastructure.persistence.JdbcUptimeDealRepository;
import com.example.monitor.infrastructure.scheduling.DealSettlementScheduler;
import com.example.monitor.infrastructure.scheduling.DefaultServiceSubscriber;
import com.example.monitor.infrastructure.probe.HttpHealthProbe;
import com.example.monitor.infrastructure.solana.HttpSolanaRpc;
import com.example.monitor.infrastructure.solana.LoggingDowntimePublisher;
import com.example.monitor.infrastructure.solana.OracleKey;
import com.example.monitor.infrastructure.solana.SolanaDealChain;
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
	TrackingEventStore trackingEventStore(JdbcClient jdbc, TransactionTemplate transactions) {
		return new JdbcTrackingEventStore(jdbc, transactions);
	}

	@Bean
	HealthProbe healthProbe(MonitorProperties properties) {
		return new HttpHealthProbe(RestClient.builder()
			.requestFactory(HttpTimeouts.timeouts(Duration.ofMillis(properties.probeTimeoutMs()))));
	}

	@Bean
	TrackingService trackingService(TrackingEventStore store, HealthProbe probe, DowntimePublisher publisher,
			Clock clock, MonitorProperties properties, List<TrackingEventListener> listeners) {
		return new TrackingService(store, probe, publisher, clock, Duration.ofMillis(properties.checkIntervalMs()),
				listeners);
	}

	@Bean
	UptimeDealRepository uptimeDealRepository(JdbcClient jdbc) {
		return new JdbcUptimeDealRepository(jdbc);
	}

	@Bean
	UptimeHistory uptimeHistory(TrackingEventStore store, Clock clock, MonitorProperties properties) {
		return new UptimeHistory(store, clock, properties.history().defaultRangeSeconds(),
				properties.history().maxRangeSeconds());
	}

	@Bean
	DefaultServiceSubscriber defaultServiceSubscriber(TrackingService tracking, MonitorProperties properties) {
		MonitorProperties.DefaultService service = properties.defaultService();
		return new DefaultServiceSubscriber(tracking, service.enabled() ? new ServiceId(service.id()) : null,
				service.healthUrl());
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
		DealChain dealChain(SolanaRpc rpc, OracleKey oracle, MonitorProperties properties) {
			MonitorProperties.Blockchain chain = properties.blockchain();
			return new SolanaDealChain(rpc, oracle, properties.deal().programId(), chain.rpcUrl(),
					chain.oracleMinLamports(), chain.oracleAirdropLamports());
		}

		/** The deal oracle; it is also a {@link TrackingEventListener}, so {@code TrackingService} feeds it. */
		@Bean
		DealService dealService(UptimeDealRepository deals, DealChain chain, TrackingEventStore events, Clock clock,
				MonitorProperties properties) {
			MonitorProperties.Deal deal = properties.deal();
			MonitorProperties.DefaultService service = properties.defaultService();
			return new DealService(deals, chain, events, clock,
					new DealService.Settings(service.enabled() ? new ServiceId(service.id()) : null,
							deal.maxDurationSeconds(), deal.settleGraceSeconds(), deal.maxSettleAttempts(),
							deal.confirmTimeoutSeconds(), Math.max(1, properties.checkIntervalMs() / 1_000)));
		}

		@Bean
		@ConditionalOnProperty(name = "monitor.scheduler.enabled", havingValue = "true", matchIfMissing = true)
		DealSettlementScheduler dealSettlementScheduler(DealService deals) {
			return new DealSettlementScheduler(deals);
		}

		@Bean
		DowntimePublisher solanaDowntimePublisher(SolanaRpc rpc, OracleKey oracle, MonitorProperties properties) {
			return new SolanaMemoDowntimePublisher(rpc, oracle, properties.blockchain().oracleMinLamports(),
					properties.blockchain().oracleAirdropLamports());
		}

	}

}
