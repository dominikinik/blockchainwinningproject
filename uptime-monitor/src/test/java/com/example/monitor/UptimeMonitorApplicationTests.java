package com.example.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

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
import com.example.monitor.application.TrackingService;
import com.example.monitor.domain.DowntimePublisher;
import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.infrastructure.config.MonitorProperties;
import com.example.monitor.infrastructure.scheduling.HealthCheckScheduler;
import com.example.monitor.infrastructure.solana.SolanaMemoDowntimePublisher;
import com.example.monitor.infrastructure.solana.OracleKey;
import com.example.monitor.infrastructure.solana.SolanaRpc;
import com.example.monitor.infrastructure.solana.SolanaRpc.AccountInfo;
import com.example.monitor.support.DealFixtures;
import com.example.monitor.infrastructure.solana.SolanaRpc.SolanaRpcException;

/**
 * Starts the whole application with a mocked health probe and Solana RPC, and drives checks by hand
 * through {@link TrackingService#checkActive()} instead of the scheduler (disabled in the test profile).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UptimeMonitorApplicationTests {

	static final String URL = "http://provider:8080/api/health";

	@Autowired
	MockMvc mvc;

	@Autowired
	ApplicationContext context;

	@Autowired
	TrackingService tracking;

	@Autowired
	DealService deals;

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
	void startsWithTheConfiguredComponents() {
		MonitorProperties properties = context.getBean(MonitorProperties.class);
		assertThat(properties.checkIntervalMs()).isEqualTo(2000);
		assertThat(properties.probeTimeoutMs()).isLessThan(properties.checkIntervalMs());
		assertThat(properties.blockchain().enabled()).isTrue();
		assertThat(context.getBean(DowntimePublisher.class)).isInstanceOf(SolanaMemoDowntimePublisher.class);
		assertThat(context.getBeansOfType(HealthCheckScheduler.class)).isEmpty();
	}

	@Test
	void healthAndApiDocsAreServed() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/subscriptions']").exists())
			.andExpect(jsonPath("$.paths['/api/deals']").exists())
			.andExpect(jsonPath("$.paths['/api/uptime']").exists());
	}

	@Test
	void fullTrackingLifecycleOverHttp() throws Exception {
		String id = subscribe(null);
		mvc.perform(get("/api/subscriptions/" + id + "/events")).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].type").value("TrackingStarted"))
			.andExpect(jsonPath("$[0].serviceId").value(id))
			.andExpect(jsonPath("$[0].detail").value(URL));
		verify(rpc, never()).sendTransaction(any());

		when(probe.check(URL)).thenReturn(HealthCheckResult.fromResponse(200, "UP"),
				HealthCheckResult.fromResponse(200, "DOWN"), HealthCheckResult.fromResponse(404, null),
				HealthCheckResult.fromResponse(500, null));
		for (int i = 0; i < 4; i++) {
			tracking.check(ServiceId.of(id));
		}
		verify(rpc, times(3)).sendTransaction(any());

		mvc.perform(get("/api/subscriptions/" + id)).andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.checkIntervalMs").value(2000))
			.andExpect(jsonPath("$.totalDowntimeMs").value(4000))
			.andExpect(jsonPath("$.downtimeChecks").value(2))
			.andExpect(jsonPath("$.internalErrors").value(1));

		mvc.perform(delete("/api/subscriptions/" + id)).andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false))
			.andExpect(jsonPath("$.finishedAt").exists())
			.andExpect(jsonPath("$.totalDowntimeMs").value(4000));
		verify(rpc, times(4)).sendTransaction(any());

		mvc.perform(get("/api/subscriptions/" + id + "/events")).andExpect(status().isOk())
			.andExpect(jsonPath("$[*].type").value(org.hamcrest.Matchers.contains("TrackingStarted",
					"HealthCheckSucceeded", "Downtime", "Downtime", "InternalErrorHappened", "TrackingFinished")))
			.andExpect(jsonPath("$[2].httpStatus").value(200))
			.andExpect(jsonPath("$[3].httpStatus").value(404))
			.andExpect(jsonPath("$[4].httpStatus").value(500));
		mvc.perform(get("/api/subscriptions")).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.serviceId == '" + id + "')]").exists());
	}

	@Test
	void subscribeUnderAGivenUuidAndResume() throws Exception {
		String id = UUID.randomUUID().toString();
		assertThat(subscribe(id)).isEqualTo(id);
		mvc.perform(post("/api/subscriptions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"healthUrl\":\"" + URL + "\",\"serviceId\":\"" + id + "\"}"))
			.andExpect(status().isConflict());
		mvc.perform(delete("/api/subscriptions/" + id)).andExpect(status().isOk());
		mvc.perform(delete("/api/subscriptions/" + id)).andExpect(status().isConflict());
		assertThat(subscribe(id)).isEqualTo(id);
	}

	@Test
	void checkActiveUsesTheProbe() throws Exception {
		String id = subscribe(null);
		when(probe.check(anyString())).thenReturn(HealthCheckResult.unreachable("Connection refused"));

		tracking.checkActive();

		mvc.perform(get("/api/subscriptions/" + id + "/events"))
			.andExpect(jsonPath("$[1].type").value("InternalErrorHappened"))
			.andExpect(jsonPath("$[1].httpStatus").doesNotExist())
			.andExpect(jsonPath("$[1].detail").value("Connection refused"));
		mvc.perform(delete("/api/subscriptions/" + id)).andExpect(status().isOk());
	}

	@Test
	void blockchainFailureDoesNotFailTheRequest() throws Exception {
		when(rpc.sendTransaction(any())).thenThrow(new SolanaRpcException("node down"));
		String id = subscribe(null);

		mvc.perform(delete("/api/subscriptions/" + id)).andExpect(status().isOk())
			.andExpect(jsonPath("$.eventCount").value(2));
	}

	@Test
	void invalidRequestsAreBadRequests() throws Exception {
		mvc.perform(post("/api/subscriptions").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("healthUrl is required"));
		mvc.perform(post("/api/subscriptions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"healthUrl\":\"ftp://x/health\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/subscriptions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"healthUrl\":\"" + URL + "\",\"serviceId\":\"nope\"}")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/subscriptions/not-a-uuid")).andExpect(status().isBadRequest());
	}

	@Test
	void unknownServicesAreNotFound() throws Exception {
		String unknown = UUID.randomUUID().toString();
		mvc.perform(get("/api/subscriptions/" + unknown)).andExpect(status().isNotFound());
		mvc.perform(get("/api/subscriptions/" + unknown + "/events")).andExpect(status().isNotFound());
		mvc.perform(delete("/api/subscriptions/" + unknown)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("Service " + unknown + " was never tracked"));
	}

	@Test
	void aDowntimeIsReportedToTheContractThroughTheWiring() throws Exception {
		String service = subscribe(null);
		String deal = DealFixtures.newAddress();
		when(rpc.getAccountInfo(deal)).thenReturn(new AccountInfo(DealFixtures.PROGRAM_ID, 1, DealFixtures.dealData(
				DealFixtures.newAddress(), DealFixtures.newAddress(), oracle.address(), 1, 5_000_000,
				Instant.now().getEpochSecond(), 10)));

		mvc.perform(get("/api/deals/config")).andExpect(status().isOk())
			.andExpect(jsonPath("$.programId").value(DealFixtures.PROGRAM_ID))
			.andExpect(jsonPath("$.oracle").value(oracle.address()))
			.andExpect(jsonPath("$.checkIntervalSeconds").value(2));
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON)
			.content("{\"address\":\"" + deal + "\",\"serviceId\":\"" + service + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.serviceId").value(service))
			.andExpect(jsonPath("$.durationSeconds").value(10))
			.andExpect(jsonPath("$.endsAt").exists())
			.andExpect(jsonPath("$.upChecks").doesNotExist());

		when(probe.check(URL)).thenReturn(HealthCheckResult.fromResponse(404, null));
		tracking.check(ServiceId.of(service));
		// The monitor reports observations; SLA totals and the payout decision stay on chain.
		mvc.perform(get("/api/deals/" + deal))
			.andExpect(jsonPath("$.upChecks").doesNotExist())
			.andExpect(jsonPath("$.totalRounds").doesNotExist());

		deals.settleDue();
		mvc.perform(get("/api/deals/" + deal)).andExpect(jsonPath("$.signature").doesNotExist());
		mvc.perform(get("/api/deals")).andExpect(jsonPath("$[?(@.address == '" + deal + "')]").exists());

		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON)
			.content("{\"address\":\"" + deal + "\",\"serviceId\":\"" + service + "\"}"))
			.andExpect(status().isConflict());
		mvc.perform(delete("/api/subscriptions/" + service)).andExpect(status().isOk());
	}

	@Test
	void registersAProposalBeforeItsRecipientAccepts() throws Exception {
		String service = subscribe(null);
		String deal = DealFixtures.newAddress();
		long deadline = Instant.now().getEpochSecond() + 86_400;
		when(rpc.getAccountInfo(deal)).thenReturn(new AccountInfo(DealFixtures.PROGRAM_ID, 1, DealFixtures.proposalData(
				DealFixtures.newAddress(), DealFixtures.newAddress(), oracle.address(), 1, 5_000_000, 7_000_000, 10,
				deadline)));

		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON)
			.content("{\"address\":\"" + deal + "\",\"serviceId\":\"" + service + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PROPOSED"))
			.andExpect(jsonPath("$.amountLamports").value(5_000_000))
			.andExpect(jsonPath("$.guaranteeLamports").value(7_000_000))
			.andExpect(jsonPath("$.acceptDeadline").value(Instant.ofEpochSecond(deadline).toString()))
			.andExpect(jsonPath("$.startsAt").doesNotExist())
			.andExpect(jsonPath("$.endsAt").doesNotExist());
		mvc.perform(get("/api/deals/" + deal)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROPOSED"));
		mvc.perform(delete("/api/subscriptions/" + service)).andExpect(status().isOk());
	}

	@Test
	void dealErrorsMapToProblems() throws Exception {
		String service = subscribe(null);
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON)
			.content("{\"address\":\"" + DealFixtures.newAddress() + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("serviceId is required")));
		String unreachable = DealFixtures.newAddress();
		when(rpc.getAccountInfo(unreachable)).thenThrow(new SolanaRpcException("node down"));
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON)
			.content("{\"address\":\"" + unreachable + "\",\"serviceId\":\"" + service + "\"}"))
			.andExpect(status().isBadGateway());
		mvc.perform(get("/api/deals/" + DealFixtures.newAddress())).andExpect(status().isNotFound());
		mvc.perform(delete("/api/subscriptions/" + service)).andExpect(status().isOk());
	}

	@Test
	void uptimeHistoryIsServedPerSecond() throws Exception {
		String service = subscribe(null);
		mvc.perform(get("/api/uptime").param("serviceId", service).param("from", "2026-01-01T00:00:00Z")
			.param("to", "2026-01-01T00:00:02Z"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].time").value("2026-01-01T00:00:00Z"))
			.andExpect(jsonPath("$[0].down").value(true));
		mvc.perform(get("/api/uptime").param("serviceId", service)).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(300));
		mvc.perform(get("/api/uptime")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("serviceId is required")));
		mvc.perform(get("/api/uptime").param("serviceId", service).param("from", "2026-01-02T00:00:00Z")
			.param("to", "2026-01-01T00:00:00Z")).andExpect(status().isBadRequest());
		mvc.perform(delete("/api/subscriptions/" + service)).andExpect(status().isOk());
	}

	private String subscribe(String serviceId) throws Exception {
		String body = serviceId == null ? "{\"healthUrl\":\"" + URL + "\"}"
				: "{\"healthUrl\":\"" + URL + "\",\"serviceId\":\"" + serviceId + "\"}";
		String response = mvc.perform(post("/api/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.totalDowntimeMs").value(0))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(response, "$.serviceId");
	}

}
