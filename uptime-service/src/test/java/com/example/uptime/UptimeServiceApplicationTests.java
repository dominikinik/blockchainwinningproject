package com.example.uptime;

import static com.example.uptime.support.DealFixtures.PROGRAM_ID;
import static com.example.uptime.support.DealFixtures.dealData;
import static com.example.uptime.support.DealFixtures.newAddress;
import static com.example.uptime.support.DealFixtures.proposalData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.uptime.deal.DealService;
import com.example.uptime.monitor.UptimeSampler;
import com.example.uptime.solana.OracleKey;
import com.example.uptime.solana.SolanaRpc;
import com.example.uptime.solana.SolanaRpc.AccountInfo;
import com.example.uptime.solana.SolanaRpc.SolanaRpcException;
import com.example.uptime.state.ApplicationStateService;
import com.example.uptime.uptime.UptimeQueryService;
import com.example.uptime.uptime.UptimeRecord;
import com.example.uptime.uptime.UptimeRecordRepository;

/**
 * Starts the whole application against in-memory H2 (test profile) and exercises it over HTTP. Records
 * written here use dates in 2000 so they never collide with what the live sampler writes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UptimeServiceApplicationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	UptimeRecordRepository repository;

	@Autowired
	ApplicationStateService state;

	@Autowired
	ApplicationContext context;

	@Autowired
	OracleKey oracle;

	@MockitoBean
	SolanaRpc rpc;

	@AfterEach
	void reset() {
		state.start();
	}

	@Test
	void applicationStartsWithAllComponentsAndUp() {
		assertThat(context.getBean(UptimeSampler.class)).isNotNull();
		assertThat(context.getBean(UptimeQueryService.class)).isNotNull();
		assertThat(context.getBean(DealService.class).oracleAddress()).isEqualTo(oracle.address());
		assertThat(context.getBean(Clock.class).getZone()).isEqualTo(Clock.systemUTC().getZone());
		UptimeProperties properties = context.getBean(UptimeProperties.class);
		assertThat(properties.sampleIntervalMs()).isEqualTo(10);
		assertThat(properties.flushIntervalMs()).isEqualTo(1000);
		assertThat(properties.maxRangeSeconds()).isEqualTo(86400);
		assertThat(properties.defaultRangeSeconds()).isEqualTo(300);
		assertThat(state.isUp()).isTrue();
	}

	@Test
	void samplerAndFlushAreScheduled() {
		List<String> tasks = context.getBeansOfType(ScheduledTaskHolder.class)
			.values()
			.stream()
			.map(ScheduledTaskHolder::getScheduledTasks)
			.flatMap(Collection::stream)
			.map(ScheduledTask::toString)
			.toList();
		assertThat(tasks).anyMatch(t -> t.contains("UptimeSampler.sample"));
		assertThat(tasks).anyMatch(t -> t.contains("UptimeSampler.flush"));
		assertThat(tasks).anyMatch(t -> t.contains("DealService.settleDue"));
	}

	@Test
	void dealConfigNamesTheProgramAndThisServicesOracle() throws Exception {
		mvc.perform(get("/api/deals/config"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.programId").value(PROGRAM_ID))
			.andExpect(jsonPath("$.oracle").value(oracle.address()))
			.andExpect(jsonPath("$.rpcUrl").value("http://127.0.0.1:8899"));
	}

	@Test
	void registersAndReadsADeal() throws Exception {
		String deal = newAddress();
		String recipient = newAddress();
		when(rpc.getAccountInfo(deal))
			.thenReturn(new AccountInfo(PROGRAM_ID, 1, dealData(newAddress(), recipient, oracle.address(), 1, 5_000_000, Instant.now().getEpochSecond(), 10)));

		mvc.perform(registerDeal(deal))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.address").value(deal))
			.andExpect(jsonPath("$.recipient").value(recipient))
			.andExpect(jsonPath("$.amountLamports").value(5_000_000))
			.andExpect(jsonPath("$.durationSeconds").value(10))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(get("/api/deals/" + deal)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(get("/api/deals")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.address == '" + deal + "')]").exists());

		mvc.perform(registerDeal(deal)).andExpect(status().isConflict());
	}

	@Test
	void registersAProposalBeforeItsRecipientAccepts() throws Exception {
		String deal = newAddress();
		long deadline = Instant.now().getEpochSecond() + 86_400;
		when(rpc.getAccountInfo(deal)).thenReturn(new AccountInfo(PROGRAM_ID, 1,
				proposalData(newAddress(), newAddress(), oracle.address(), 1, 5_000_000, 7_000_000, 10, deadline)));

		mvc.perform(registerDeal(deal))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PROPOSED"))
			.andExpect(jsonPath("$.amountLamports").value(5_000_000))
			.andExpect(jsonPath("$.guaranteeLamports").value(7_000_000))
			.andExpect(jsonPath("$.acceptDeadline").value(Instant.ofEpochSecond(deadline).toString()))
			.andExpect(jsonPath("$.startsAt").doesNotExist())
			.andExpect(jsonPath("$.endsAt").doesNotExist());
		mvc.perform(get("/api/deals/" + deal)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROPOSED"));
	}

	@Test
	void registerIgnoresACallerDurationAndRejectsOutOfRangeOnChainOnes() throws Exception {
		String deal = newAddress();
		when(rpc.getAccountInfo(deal))
			.thenReturn(new AccountInfo(PROGRAM_ID, 1, dealData(newAddress(), newAddress(), oracle.address(), 1, 5, 1_000, 7)));
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON)
			.content("{\"address\":\"" + deal + "\",\"durationSeconds\":1}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.durationSeconds").value(7))
			.andExpect(jsonPath("$.startsAt").exists());

		String tooLong = newAddress();
		when(rpc.getAccountInfo(tooLong))
			.thenReturn(new AccountInfo(PROGRAM_ID, 1, dealData(newAddress(), newAddress(), oracle.address(), 1, 5, 1_000, 3601)));
		mvc.perform(registerDeal(tooLong)).andExpect(status().isBadRequest());
	}

	@Test
	void unexpectedIllegalStateExceptionsAreNotMappedToConflicts() {
		String deal = newAddress();
		when(rpc.getAccountInfo(deal)).thenThrow(new IllegalStateException("boom"));
		// No handler maps it, so MockMvc surfaces it as the container's 500 would.
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> mvc.perform(registerDeal(deal)))
			.hasRootCauseInstanceOf(IllegalStateException.class);
	}

	@Test
	void dealErrorsAreProblems() throws Exception {
		String failing = newAddress();
		when(rpc.getAccountInfo(failing)).thenThrow(new SolanaRpcException("getAccountInfo failed: connection refused"));

		mvc.perform(get("/api/deals/" + newAddress())).andExpect(status().isNotFound());
		mvc.perform(registerDeal(newAddress()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("No uptime_deal account")));
		mvc.perform(registerDeal(failing)).andExpect(status().isBadGateway());
		mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON).content("not json"))
			.andExpect(status().isBadRequest());
	}

	private static org.springframework.test.web.servlet.RequestBuilder registerDeal(String address) {
		return post("/api/deals").contentType(MediaType.APPLICATION_JSON)
			.content("{\"address\":\"" + address + "\"}");
	}

	@Test
	void stopAndStartSwitchHealthEndpointAndState() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/api/application/state")).andExpect(jsonPath("$.status").value("UP"));

		mvc.perform(post("/api/application/stop")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(get("/api/application/state")).andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(get("/actuator/health"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value("DOWN"))
			.andExpect(jsonPath("$.components.applicationState.details.reason").value("stopped via API"));

		mvc.perform(post("/api/application/start")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void repeatedStopAndStartAreIdempotent() throws Exception {
		mvc.perform(post("/api/application/stop"));
		mvc.perform(post("/api/application/stop")).andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(post("/api/application/start"));
		mvc.perform(post("/api/application/start")).andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void stateEndpointsRejectWrongMethod() throws Exception {
		mvc.perform(get("/api/application/stop")).andExpect(status().isMethodNotAllowed());
		mvc.perform(post("/api/application/state")).andExpect(status().isMethodNotAllowed());
	}

	@Test
	void atReportsRecordedAndMissingSeconds() throws Exception {
		repository.save(new UptimeRecord(Instant.parse("2000-01-01T00:00:01Z"), true, 100, 100));
		repository.save(new UptimeRecord(Instant.parse("2000-01-01T00:00:02Z"), false, 100, 40));

		mvc.perform(get("/api/uptime/at").param("time", "2000-01-01T00:00:00.500Z"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.time").value("2000-01-01T00:00:00Z"))
			.andExpect(jsonPath("$.down").value(true));
		mvc.perform(get("/api/uptime/at").param("time", "2000-01-01T00:00:01.999Z"))
			.andExpect(jsonPath("$.time").value("2000-01-01T00:00:01Z"))
			.andExpect(jsonPath("$.down").value(false));
		mvc.perform(get("/api/uptime/at").param("time", "2000-01-01T00:00:02Z"))
			.andExpect(jsonPath("$.down").value(true));
	}

	@Test
	void listReturnsEverySecondWithGapsAsDown() throws Exception {
		repository.save(new UptimeRecord(Instant.parse("2000-01-02T00:00:01Z"), true, 100, 100));

		mvc.perform(get("/api/uptime").param("from", "2000-01-02T00:00:00Z").param("to", "2000-01-02T00:00:02Z"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].down").value(true))
			.andExpect(jsonPath("$[1].time").value("2000-01-02T00:00:01Z"))
			.andExpect(jsonPath("$[1].down").value(false))
			.andExpect(jsonPath("$[2].down").value(true));
	}

	@Test
	void listWithoutParametersReturnsDefaultRange() throws Exception {
		mvc.perform(get("/api/uptime")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(300));
	}

	@Test
	void invalidRangeIsBadRequestProblem() throws Exception {
		mvc.perform(get("/api/uptime").param("from", "2000-01-02T00:00:00Z").param("to", "2000-01-01T00:00:00Z"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("'from' must not be after 'to'"));
		mvc.perform(get("/api/uptime").param("from", "2000-01-01T00:00:00Z").param("to", "2000-01-03T00:00:00Z"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Range of 172801s exceeds maximum of 86400s"));
	}

	@Test
	void malformedOrMissingParametersAreBadRequest() throws Exception {
		mvc.perform(get("/api/uptime/at")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/uptime/at").param("time", "yesterday")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/uptime").param("from", "not-a-date")).andExpect(status().isBadRequest());
	}

	@Test
	void swaggerUiAndApiDocsAreServed() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/uptime']").exists())
			.andExpect(jsonPath("$.paths['/api/uptime/at']").exists())
			.andExpect(jsonPath("$.paths['/api/application/stop']").exists())
			.andExpect(jsonPath("$.paths['/api/application/start']").exists())
			.andExpect(jsonPath("$.paths['/api/application/state']").exists())
			.andExpect(jsonPath("$.paths['/api/deals']").exists())
			.andExpect(jsonPath("$.paths['/api/deals/config']").exists());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

}
