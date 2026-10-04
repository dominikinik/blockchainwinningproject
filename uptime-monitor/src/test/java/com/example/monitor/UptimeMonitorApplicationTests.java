package com.example.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.monitor.application.DealService;
import com.example.monitor.application.HealthRelay;
import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.infrastructure.config.MonitorProperties;
import com.example.monitor.infrastructure.scheduling.DealSettlementScheduler;
import com.example.monitor.infrastructure.scheduling.HealthCheckScheduler;
import com.example.monitor.infrastructure.solana.OracleKey;
import com.example.monitor.infrastructure.solana.SolanaRpc;
import com.example.monitor.infrastructure.solana.SolanaRpc.AccountInfo;
import com.example.monitor.infrastructure.solana.SolanaRpc.SolanaRpcException;
import com.example.monitor.support.DealFixtures;

/**
 * Starts the whole application with a mocked health probe and Solana RPC, and relays and settles by hand through
 * {@link HealthRelay#relay()} and {@link DealService#settleDue()} instead of the schedulers (disabled in the test
 * profile).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UptimeMonitorApplicationTests {

	static final String URL = "http://provider.test/api/health";

	@Autowired
	MockMvc mvc;

	@Autowired
	ApplicationContext context;

	@Autowired
	HealthRelay relay;

	@Autowired
	DealService deals;

	@Autowired
	MonitorProperties properties;

	@Autowired
	OracleKey oracle;

	@MockitoBean
	SolanaRpc rpc;

	@MockitoBean
	HealthProbe probe;

	@BeforeEach
	void setUp() {
		when(rpc.getLatestBlockhash()).thenReturn(new byte[32]);
		when(rpc.sendTransaction(any())).thenReturn("sig");
	}

	@Test
	void startsWithTheConfiguredComponents() throws Exception {
		assertThat(properties.checkIntervalMs()).isEqualTo(2000);
		assertThat(properties.probeTimeoutMs()).isLessThan(properties.checkIntervalMs());
		assertThat(properties.serviceId()).hasToString("00000000-0000-0000-0000-000000008080");
		assertThat(context.getBeanNamesForType(HealthCheckScheduler.class)).isEmpty();
		assertThat(context.getBeanNamesForType(DealSettlementScheduler.class)).isEmpty();
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/deals']").exists());
	}

	@Test
	void aRegisteredDealGetsAHeartbeatAtItsOwnIntervalThroughTheWiring() throws Exception {
		String deal = DealFixtures.newAddress();
		when(rpc.getAccountInfo(deal)).thenReturn(new AccountInfo(DealFixtures.PROGRAM_ID, 1, DealFixtures.dealData(
				DealFixtures.newAddress(), DealFixtures.newAddress(), oracle.address(), 1, 5_000_000,
				Instant.now().getEpochSecond() - 4, 600)));

		mvc.perform(get("/api/deals/config")).andExpect(status().isOk())
			.andExpect(jsonPath("$.programId").value(DealFixtures.PROGRAM_ID))
			.andExpect(jsonPath("$.oracle").value(oracle.address()))
			.andExpect(jsonPath("$.checkIntervalSeconds").value(2));
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"" + deal + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.serviceId").value(properties.serviceId().toString()))
			.andExpect(jsonPath("$.durationSeconds").value(600))
			.andExpect(jsonPath("$.endsAt").exists())
			.andExpect(jsonPath("$.upChecks").doesNotExist());

		// The dashboard sampler doesn't report to deals; the deal's heartbeat runs in settleDue.
		when(probe.check(URL)).thenReturn(HealthCheckResult.fromResponse(200, "UP"));
		relay.relay();
		verify(rpc, never()).sendTransaction(any());
		when(probe.check(URL)).thenReturn(HealthCheckResult.fromResponse(404, null));
		deals.settleDue();
		verify(rpc).sendTransaction(any());

		// The heartbeat is logged with its probe result and the sent observation.
		mvc.perform(get("/api/deals/" + deal + "/heartbeats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].dealAddress").value(deal))
			.andExpect(jsonPath("$[0].round").isNumber())
			.andExpect(jsonPath("$[0].up").value(false))
			.andExpect(jsonPath("$[0].outcome").value("DOWN"))
			.andExpect(jsonPath("$[0].httpStatus").value(404))
			.andExpect(jsonPath("$[0].report").value("SENT"))
			.andExpect(jsonPath("$[0].signature").value("sig"))
			.andExpect(jsonPath("$[0].latencyMs").isNumber())
			.andExpect(jsonPath("$[0].checkedAt").exists());
		mvc.perform(get("/api/heartbeats").param("limit", "500"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.dealAddress == '" + deal + "')]").exists());

		// The monitor reports observations; SLA totals and the payout decision stay on chain.
		mvc.perform(get("/api/deals/" + deal))
			.andExpect(jsonPath("$.signature").doesNotExist())
			.andExpect(jsonPath("$.totalRounds").doesNotExist());
		mvc.perform(get("/api/deals")).andExpect(jsonPath("$[?(@.address == '" + deal + "')]").exists());
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"" + deal + "\"}"))
			.andExpect(status().isConflict());
	}

	@Test
	void registersAProposalBeforeItsRecipientAccepts() throws Exception {
		String deal = DealFixtures.newAddress();
		long deadline = Instant.now().getEpochSecond() + 86_400;
		when(rpc.getAccountInfo(deal)).thenReturn(new AccountInfo(DealFixtures.PROGRAM_ID, 1, DealFixtures.proposalData(
				DealFixtures.newAddress(), DealFixtures.newAddress(), oracle.address(), 1, 5_000_000, 7_000_000, 10,
				deadline)));

		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"" + deal + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PROPOSED"))
			.andExpect(jsonPath("$.amountLamports").value(5_000_000))
			.andExpect(jsonPath("$.guaranteeLamports").value(7_000_000))
			.andExpect(jsonPath("$.acceptDeadline").value(Instant.ofEpochSecond(deadline).toString()))
			.andExpect(jsonPath("$.startsAt").doesNotExist())
			.andExpect(jsonPath("$.endsAt").doesNotExist());
		mvc.perform(get("/api/deals/" + deal)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROPOSED"));

		when(probe.check(URL)).thenReturn(HealthCheckResult.fromResponse(200, "UP"));
		deals.settleDue();
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void dealErrorsMapToProblems() throws Exception {
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON)
			.content("{\"address\":\"" + DealFixtures.newAddress() + "\",\"serviceId\":\"00000000-0000-0000-0000-000000000001\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not relayed by this monitor")));
		String unreachable = DealFixtures.newAddress();
		when(rpc.getAccountInfo(unreachable)).thenThrow(new SolanaRpcException("node down"));
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON)
			.content("{\"address\":\"" + unreachable + "\"}"))
			.andExpect(status().isBadGateway());
		mvc.perform(get("/api/deals/" + DealFixtures.newAddress())).andExpect(status().isNotFound());
	}

	@Test
	void servesTheRelayedUptimePerSecond() throws Exception {
		when(probe.check(URL)).thenReturn(HealthCheckResult.fromResponse(200, "UP"));
		Instant at = relay.relay().observedAt();

		mvc.perform(get("/api/uptime").param("from", at.plusSeconds(1).toString())
			.param("to", at.plusSeconds(2).toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].down").value(false));
		mvc.perform(get("/api/uptime")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(300));
	}

	@Test
	void rejectsInvalidUptimeRanges() throws Exception {
		mvc.perform(get("/api/uptime").param("from", "2026-10-04T12:00:10Z").param("to", "2026-10-04T12:00:00Z"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/uptime").param("from", "2026-10-03T00:00:00Z").param("to", "2026-10-04T12:00:00Z"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void heartbeatReadsValidateTheirLimit() throws Exception {
		mvc.perform(get("/api/heartbeats")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
		mvc.perform(get("/api/heartbeats").param("limit", "0")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/heartbeats").param("limit", "501")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/heartbeats").param("limit", "many")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/deals/" + DealFixtures.newAddress() + "/heartbeats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/deals/x/heartbeats").param("limit", "-1")).andExpect(status().isBadRequest());
	}

	@Test
	void theTrackerEndpointsAreGone() throws Exception {
		mvc.perform(get("/api/subscriptions")).andExpect(status().isNotFound());
	}

}
