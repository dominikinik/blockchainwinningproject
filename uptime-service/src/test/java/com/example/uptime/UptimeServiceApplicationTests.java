package com.example.uptime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.uptime.state.ApplicationStateHealthIndicator;
import com.example.uptime.state.ApplicationStateService;
import com.example.uptime.web.ApplicationControlController;
import com.example.uptime.web.HealthController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UptimeServiceApplicationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	ApplicationStateService state;

	@Autowired
	ApplicationContext context;

	@AfterEach
	void resetState() {
		state.start();
	}

	@Test
	void contextStartsWithProviderBeansOnly() {
		assertThat(context.getBean(ApplicationStateService.class)).isSameAs(state);
		assertThat(context.getBean(ApplicationStateHealthIndicator.class)).isNotNull();
		assertThat(context.getBean(HealthController.class)).isNotNull();
		assertThat(context.getBean(ApplicationControlController.class)).isNotNull();
		assertThat(context.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
	}

	@Test
	void actuatorHealthFollowsTheSwitch() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));

		mvc.perform(post("/api/application/stop")).andExpect(status().isOk());
		mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(jsonPath("$.components.applicationState.details.reason").value("stopped via API"));

		mvc.perform(post("/api/application/start")).andExpect(status().isOk());
		mvc.perform(get("/actuator/health")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void apiHealthIsAlways200() throws Exception {
		mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(post("/api/application/stop")).andExpect(status().isOk());
		mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DOWN"));
	}

	@Test
	void startAndStopAreIdempotent() throws Exception {
		mvc.perform(post("/api/application/stop")).andExpect(status().isOk());
		mvc.perform(post("/api/application/stop")).andExpect(status().isOk());
		assertThat(state.isUp()).isFalse();
		mvc.perform(post("/api/application/start")).andExpect(status().isOk());
		mvc.perform(post("/api/application/start")).andExpect(status().isOk());
		assertThat(state.isUp()).isTrue();
	}

	@Test
	void getOnStopIsMethodNotAllowed() throws Exception {
		mvc.perform(get("/api/application/stop")).andExpect(status().isMethodNotAllowed());
		assertThat(state.isUp()).isTrue();
	}

	@Test
	void stateEndpointReportsTheSwitch() throws Exception {
		mvc.perform(get("/api/application/state")).andExpect(status().isOk());
		mvc.perform(post("/api/application/stop"));
		String down = mvc.perform(get("/api/application/state")).andReturn().getResponse().getContentAsString();
		mvc.perform(post("/api/application/start"));
		String up = mvc.perform(get("/api/application/state")).andReturn().getResponse().getContentAsString();
		assertThat(down).isNotEqualTo(up);
		assertThat(down).contains("DOWN");
		assertThat(up).contains("UP");
	}

	@Test
	void apiDocsListOnlyProviderPaths() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		JsonNode paths = new ObjectMapper().readTree(body).get("paths");
		Set<String> names = new TreeSet<>();
		paths.fieldNames().forEachRemaining(names::add);
		assertThat(names).contains("/api/health", "/api/application/start", "/api/application/stop",
				"/api/application/state", "/actuator/health");
		assertThat(names).noneMatch(p -> p.startsWith("/api/uptime") || p.startsWith("/api/deals"));
	}

	@Test
	void swaggerUiIsServed() throws Exception {
		mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
	}

	@Test
	void unknownPathIs404() throws Exception {
		mvc.perform(get("/api/uptime")).andExpect(status().isNotFound());
		mvc.perform(get("/api/deals")).andExpect(status().isNotFound());
	}

}
