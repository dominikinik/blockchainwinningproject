package com.example.uptime;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.uptime.monitor.UptimeSampler;
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

	@AfterEach
	void reset() {
		state.start();
	}

	@Test
	void applicationStartsWithAllComponentsAndUp() {
		assertThat(context.getBean(UptimeSampler.class)).isNotNull();
		assertThat(context.getBean(UptimeQueryService.class)).isNotNull();
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
			.andExpect(jsonPath("$.paths['/api/application/state']").exists());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

}
