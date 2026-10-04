package com.example.monitor.infrastructure.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import com.example.monitor.application.DealService;
import com.example.monitor.application.HealthRelay;
import com.example.monitor.application.UptimeHistory;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.deal.DealChain;
import com.example.monitor.domain.deal.UptimeDealRepository;
import com.example.monitor.domain.heartbeat.HeartbeatLog;
import com.example.monitor.infrastructure.persistence.JdbcHeartbeatLog;
import com.example.monitor.infrastructure.persistence.JdbcUptimeDealRepository;
import com.example.monitor.infrastructure.probe.HttpHealthProbe;
import com.example.monitor.infrastructure.scheduling.DealSettlementScheduler;
import com.example.monitor.infrastructure.solana.HttpSolanaRpc;
import com.example.monitor.infrastructure.solana.OracleKey;
import com.example.monitor.infrastructure.solana.SolanaDealChain;
import com.example.monitor.infrastructure.solana.SolanaRpc;

/** Wires the framework-free domain and application layers to their adapters. */
@Configuration
public class MonitorConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	HealthProbe healthProbe(MonitorProperties properties) {
		return new HttpHealthProbe(RestClient.builder()
			.requestFactory(HttpTimeouts.timeouts(Duration.ofMillis(properties.probeTimeoutMs()))));
	}

	@Bean
	UptimeHistory uptimeHistory(Clock clock, MonitorProperties properties) {
		return new UptimeHistory(clock, Duration.ofMillis(properties.checkIntervalMs()),
				properties.history().defaultRangeSeconds(), properties.history().maxRangeSeconds());
	}

	@Bean
	UptimeDealRepository uptimeDealRepository(JdbcClient jdbc) {
		return new JdbcUptimeDealRepository(jdbc);
	}

	@Bean
	HeartbeatLog heartbeatLog(JdbcClient jdbc) {
		return new JdbcHeartbeatLog(jdbc);
	}

	/** Samples the provider for {@code /api/uptime}; deals run their own heartbeats in {@link DealService}. */
	@Bean
	HealthRelay healthRelay(HealthProbe probe, UptimeHistory history, Clock clock, MonitorProperties properties) {
		return new HealthRelay(probe, properties.healthUrl(), history, clock);
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

		/** The deal oracle: checks the provider once per round of each deal's own interval and settles the deals. */
		@Bean
		DealService dealService(UptimeDealRepository deals, DealChain chain, HealthProbe probe,
				HeartbeatLog heartbeats, Clock clock, MonitorProperties properties) {
			MonitorProperties.Deal deal = properties.deal();
			return new DealService(deals, chain, probe, heartbeats, clock,
					new DealService.Settings(new ServiceId(properties.serviceId()), properties.healthUrl(),
							deal.maxDurationSeconds(),
							deal.settleGraceSeconds(), deal.maxSettleAttempts(), deal.confirmTimeoutSeconds(),
							Math.max(1, properties.checkIntervalMs() / 1_000)));
		}

		@Bean
		@ConditionalOnProperty(name = "monitor.scheduler.enabled", havingValue = "true", matchIfMissing = true)
		DealSettlementScheduler dealSettlementScheduler(DealService deals) {
			return new DealSettlementScheduler(deals);
		}

	}

}
