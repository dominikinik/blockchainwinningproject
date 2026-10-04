package com.example.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.example.monitor.application.DealService;
import com.example.monitor.application.TrackingService;
import com.example.monitor.domain.DowntimePublisher;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.infrastructure.probe.HttpHealthProbe;
import com.example.monitor.infrastructure.scheduling.HealthCheckScheduler;
import com.example.monitor.infrastructure.solana.LoggingDowntimePublisher;
import com.example.monitor.infrastructure.solana.OracleKey;
import com.example.monitor.infrastructure.solana.SolanaRpc;

/**
 * With the blockchain off, reports are only logged, no Solana beans or deal oracle exist and /api/deals answers
 * 503; the schedulers are on, and the configured default service is tracked from startup.
 */
@SpringBootTest(properties = { "monitor.blockchain.enabled=false", "monitor.scheduler.enabled=true",
		"monitor.check-interval-ms=600000", "monitor.default-service.id=00000000-0000-0000-0000-000000000042",
		"monitor.default-service.health-url=http://127.0.0.1:9/api/health" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BlockchainDisabledTests {

	@Autowired
	ApplicationContext context;

	@Autowired
	MockMvc mvc;

	@Autowired
	TrackingService tracking;

	@Test
	void theDealOracleIsOffAndTheDefaultServiceIsTracked() throws Exception {
		assertThat(context.getBeansOfType(DealService.class)).isEmpty();
		mvc.perform(get("/api/deals/config")).andExpect(status().isServiceUnavailable());
		mvc.perform(get("/api/deals")).andExpect(status().isServiceUnavailable());
		assertThat(tracking.isActive(ServiceId.of("00000000-0000-0000-0000-000000000042"))).isTrue();
		mvc.perform(get("/api/uptime")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(300));
	}

	@Test
	void usesTheLoggingPublisherAndTheRealProbeAndScheduler() {
		assertThat(context.getBean(DowntimePublisher.class)).isInstanceOf(LoggingDowntimePublisher.class);
		assertThat(context.getBeansOfType(SolanaRpc.class)).isEmpty();
		assertThat(context.getBeansOfType(OracleKey.class)).isEmpty();
		assertThat(context.getBean(HealthProbe.class)).isInstanceOf(HttpHealthProbe.class);
		assertThat(context.getBean(HealthCheckScheduler.class)).isNotNull();
	}

}
