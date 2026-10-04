package com.example.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.monitor.application.HealthRelay;
import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.infrastructure.scheduling.HealthCheckScheduler;
import com.example.monitor.infrastructure.solana.DealProgram;
import com.example.monitor.infrastructure.solana.OracleKey;
import com.example.monitor.infrastructure.solana.SolanaRpc;
import com.example.monitor.infrastructure.solana.SolanaRpc.AccountInfo;
import com.example.monitor.infrastructure.solana.SolanaRpc.ProgramAccount;
import com.example.monitor.support.DealFixtures;

/**
 * Starts the whole proxy with a mocked health probe and Solana RPC, and relays by hand through
 * {@link HealthRelay#relay()} instead of the scheduler (disabled in the test profile).
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
	OracleKey oracle;

	@MockitoBean
	SolanaRpc rpc;

	@MockitoBean
	HealthProbe probe;

	@Test
	void startsAsAProxyWithoutDatabaseOrScheduler() throws Exception {
		assertThat(context.getBeanNamesForType(HealthCheckScheduler.class)).isEmpty();
		assertThat(context.containsBean("dataSource")).isFalse();
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
	}

	@Test
	void servesWhereDealsMustBeCreated() throws Exception {
		mvc.perform(get("/api/deals/config"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.programId").value(DealFixtures.PROGRAM_ID))
			.andExpect(jsonPath("$.oracle").value(oracle.address()))
			.andExpect(jsonPath("$.rpcUrl").value("http://127.0.0.1:8899"))
			.andExpect(jsonPath("$.checkIntervalSeconds").value(2));
	}

	@Test
	void relaysTheProvidersHealthToEveryActiveDealOfTheOracle() {
		long startsAt = Instant.now().getEpochSecond() - 5;
		String address = DealFixtures.newAddress();
		byte[] deal = DealFixtures.deal(DealFixtures.newAddress(), DealFixtures.newAddress(), oracle.address(), startsAt)
			.window(600, 2)
			.data();
		when(rpc.getProgramAccounts(DealFixtures.PROGRAM_ID, DealProgram.ORACLE_OFFSET, oracle.address()))
			.thenReturn(List.of(new ProgramAccount(address, new AccountInfo(DealFixtures.PROGRAM_ID, 1, deal))));
		when(rpc.getLatestBlockhash()).thenReturn(new byte[32]);
		when(rpc.sendTransaction(any())).thenReturn("Sig");
		when(probe.check(URL)).thenReturn(HealthCheckResult.fromResponse(200, "UP"));

		HealthRelay.Relay result = relay.relay();

		assertThat(result.up()).isTrue();
		assertThat(result.sent()).singleElement().satisfies(o -> {
			assertThat(o.deal()).isEqualTo(address);
			assertThat(o.up()).isTrue();
			assertThat(o.signature()).isEqualTo("Sig");
		});
	}

	@Test
	void withNoDealsNothingIsSent() {
		when(probe.check(URL)).thenReturn(HealthCheckResult.unreachable("Connection refused"));

		assertThat(relay.relay().up()).isFalse();
		verify(rpc, never()).sendTransaction(any());
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
	void theTrackerAndDealRegistryEndpointsAreGone() throws Exception {
		mvc.perform(post("/api/deals").contentType("application/json").content("{\"address\":\"x\"}"))
			.andExpect(status().is4xxClientError());
		mvc.perform(get("/api/deals/Deal1")).andExpect(status().isNotFound());
		mvc.perform(get("/api/subscriptions")).andExpect(status().isNotFound());
		verify(rpc, never()).getProgramAccounts(anyString(), anyInt(), anyString());
	}

}
