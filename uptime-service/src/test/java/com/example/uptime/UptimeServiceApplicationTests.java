package com.example.uptime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.uptime.state.ApplicationStateService;
import com.example.uptime.uptime.UptimeRecord;
import com.example.uptime.uptime.UptimeRecordRepository;

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

	@AfterEach
	void reset() {
		state.start();
	}

	@Test
	void stopAndStartSwitchHealthEndpoint() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));

		mvc.perform(post("/api/application/stop")).andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(get("/actuator/health"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value("DOWN"));

		mvc.perform(post("/api/application/start")).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void samplesAreAggregatedAndPersistedEverySecondReflectingState() throws Exception {
		Instant upFrom = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
		Thread.sleep(2500);
		state.stop();
		Instant downFrom = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
		Thread.sleep(2500);
		state.start();

		List<UptimeRecord> up = repository.findByTimestampBetweenOrderByTimestamp(upFrom, upFrom);
		assertThat(up).singleElement().satisfies(r -> {
			assertThat(r.isUp()).isTrue();
			// ~100 samples per second at 10 ms
			assertThat(r.getSamples()).isGreaterThan(50);
		});
		List<UptimeRecord> down = repository.findByTimestampBetweenOrderByTimestamp(downFrom, downFrom);
		assertThat(down).singleElement().satisfies(r -> assertThat(r.isUp()).isFalse());

		mvc.perform(get("/api/uptime/at").param("time", upFrom.toString()))
			.andExpect(jsonPath("$.down").value(false));
		mvc.perform(get("/api/uptime/at").param("time", downFrom.toString()))
			.andExpect(jsonPath("$.down").value(true));
	}

	@Test
	void missingDataIsReportedAsDown() throws Exception {
		mvc.perform(get("/api/uptime/at").param("time", "2000-01-01T00:00:00.500Z"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.time").value("2000-01-01T00:00:00Z"))
			.andExpect(jsonPath("$.down").value(true));

		repository.save(new UptimeRecord(Instant.parse("2000-01-01T00:00:01Z"), true, 100, 100));
		mvc.perform(get("/api/uptime").param("from", "2000-01-01T00:00:00Z").param("to", "2000-01-01T00:00:02Z"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].down").value(true))
			.andExpect(jsonPath("$[1].time").value("2000-01-01T00:00:01Z"))
			.andExpect(jsonPath("$[1].down").value(false))
			.andExpect(jsonPath("$[2].down").value(true));
	}

	@Test
	void invalidRangeIsBadRequest() throws Exception {
		mvc.perform(get("/api/uptime").param("from", "2000-01-02T00:00:00Z").param("to", "2000-01-01T00:00:00Z"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/uptime").param("from", "2000-01-01T00:00:00Z").param("to", "2000-01-03T00:00:00Z"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void swaggerUiAndApiDocsAreServed() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/uptime']").exists())
			.andExpect(jsonPath("$.paths['/api/application/stop']").exists());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

}
