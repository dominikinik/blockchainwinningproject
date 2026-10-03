package com.example.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.example.monitor.domain.DowntimePublisher;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.infrastructure.probe.HttpHealthProbe;
import com.example.monitor.infrastructure.scheduling.HealthCheckScheduler;
import com.example.monitor.infrastructure.solana.LoggingDowntimePublisher;
import com.example.monitor.infrastructure.solana.OracleKey;
import com.example.monitor.infrastructure.solana.SolanaRpc;

/** With the blockchain off, reports are only logged and no Solana beans exist; the scheduler is on. */
@SpringBootTest(properties = { "monitor.blockchain.enabled=false", "monitor.scheduler.enabled=true",
		"monitor.check-interval-ms=600000" })
@ActiveProfiles("test")
class BlockchainDisabledTests {

	@Autowired
	ApplicationContext context;

	@Test
	void usesTheLoggingPublisherAndTheRealProbeAndScheduler() {
		assertThat(context.getBean(DowntimePublisher.class)).isInstanceOf(LoggingDowntimePublisher.class);
		assertThat(context.getBeansOfType(SolanaRpc.class)).isEmpty();
		assertThat(context.getBeansOfType(OracleKey.class)).isEmpty();
		assertThat(context.getBean(HealthProbe.class)).isInstanceOf(HttpHealthProbe.class);
		assertThat(context.getBean(HealthCheckScheduler.class)).isNotNull();
	}

}
