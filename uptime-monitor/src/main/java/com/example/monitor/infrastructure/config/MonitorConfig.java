package com.example.monitor.infrastructure.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.example.monitor.application.HealthRelay;
import com.example.monitor.application.UptimeHistory;
import com.example.monitor.domain.DealChain;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.infrastructure.probe.HttpHealthProbe;
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

	/** Without a {@link DealChain} (blockchain disabled) the relay only probes and logs. */
	@Bean
	HealthRelay healthRelay(HealthProbe probe, ObjectProvider<DealChain> chain, UptimeHistory history, Clock clock,
			MonitorProperties properties) {
		return new HealthRelay(probe, properties.healthUrl(), chain.getIfAvailable(), history, clock);
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

	}

}
