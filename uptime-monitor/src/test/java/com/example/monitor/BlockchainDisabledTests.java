package com.example.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.monitor.application.HealthRelay;
import com.example.monitor.domain.DealChain;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.infrastructure.probe.HttpHealthProbe;
import com.example.monitor.infrastructure.scheduling.HealthCheckScheduler;
import com.example.monitor.infrastructure.solana.OracleKey;
import com.example.monitor.infrastructure.solana.SolanaRpc;

/**
 * With the blockchain off, no Solana beans exist, /api/deals/config answers 503 and the relay only probes and records;
 * the real probe and the scheduler are wired. The provider URL points at a closed port, so a relay is a fast failure.
 */
@SpringBootTest(properties = { "monitor.blockchain.enabled=false", "monitor.scheduler.enabled=true",
		"monitor.check-interval-ms=600000", "monitor.health-url=http://127.0.0.1:9/api/health" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BlockchainDisabledTests {

	@Autowired
	ApplicationContext context;

	@Autowired
	MockMvc mvc;

	@Autowired
	HealthRelay relay;

	@Test
	void noChainIsWiredAndTheConfigEndpointIsUnavailable() throws Exception {
		assertThat(context.getBeansOfType(DealChain.class)).isEmpty();
		assertThat(context.getBeansOfType(SolanaRpc.class)).isEmpty();
		assertThat(context.getBeansOfType(OracleKey.class)).isEmpty();
		mvc.perform(get("/api/deals/config")).andExpect(status().isServiceUnavailable());
	}

	@Test
	void theRelayStillProbesAndRecordsWithTheRealProbeAndScheduler() throws Exception {
		assertThat(context.getBean(HealthProbe.class)).isInstanceOf(HttpHealthProbe.class);
		assertThat(context.getBean(HealthCheckScheduler.class)).isNotNull();

		HealthRelay.Relay result = relay.relay();

		assertThat(result.up()).isFalse();
		assertThat(result.sent()).isEmpty();
		mvc.perform(get("/api/uptime")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(300));
	}

}
