package com.example.monitor.infrastructure.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthCheckResult.Outcome;
import com.example.monitor.infrastructure.config.HttpTimeouts;

class HttpHealthProbeTest {

	static final String URL = "http://provider/api/health";

	RestClient.Builder builder = RestClient.builder();

	MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	HttpHealthProbe probe = new HttpHealthProbe(builder);

	@Test
	void okWithUpIsHealthy() {
		server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));

		assertThat(probe.check(URL).outcome()).isEqualTo(Outcome.HEALTHY);
		server.verify();
	}

	@Test
	void okWithDownIsDowntime() {
		server.expect(requestTo(URL)).andRespond(withSuccess("{\"status\":\"DOWN\"}", MediaType.APPLICATION_JSON));

		HealthCheckResult result = probe.check(URL);
		assertThat(result.outcome()).isEqualTo(Outcome.DOWN);
		assertThat(result.httpStatus()).isEqualTo(200);
	}

	@Test
	void okWithoutJsonIsHealthy() {
		server.expect(requestTo(URL)).andRespond(withSuccess("fine", MediaType.TEXT_PLAIN));
		assertThat(probe.check(URL).outcome()).isEqualTo(Outcome.HEALTHY);
	}

	@Test
	void okWithMalformedJsonIsHealthy() {
		server.expect(requestTo(URL)).andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));
		assertThat(probe.check(URL).outcome()).isEqualTo(Outcome.HEALTHY);
	}

	@Test
	void notFoundIsDowntime() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		assertThat(probe.check(URL)).isEqualTo(new HealthCheckResult(Outcome.DOWN, 404, "HTTP 404"));
	}

	@Test
	void serverErrorIsInternalErrorEvenWithADownBody() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"status\":\"DOWN\"}"));
		assertThat(probe.check(URL)).isEqualTo(new HealthCheckResult(Outcome.INTERNAL_ERROR, 503, "HTTP 503"));
	}

	@Test
	void refusedConnectionIsInternalErrorWithoutStatus() throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = socket.getLocalPort();
		}
		HttpHealthProbe real = new HttpHealthProbe(
				RestClient.builder().requestFactory(HttpTimeouts.timeouts(Duration.ofMillis(500))));

		HealthCheckResult result = real.check("http://127.0.0.1:" + port + "/api/health");

		assertThat(result.outcome()).isEqualTo(Outcome.INTERNAL_ERROR);
		assertThat(result.httpStatus()).isNull();
		assertThat(result.detail()).isNotBlank();
	}

	@Test
	void silentServerTimesOutAsInternalError() throws Exception {
		try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			HttpHealthProbe real = new HttpHealthProbe(
					RestClient.builder().requestFactory(HttpTimeouts.timeouts(Duration.ofMillis(200))));

			HealthCheckResult result = assertTimeoutPreemptively(Duration.ofSeconds(3),
					() -> real.check("http://127.0.0.1:" + silent.getLocalPort() + "/api/health"));

			assertThat(result.outcome()).isEqualTo(Outcome.INTERNAL_ERROR);
			assertThat(result.httpStatus()).isNull();
		}
	}

	@Test
	void invalidUrlIsInternalError() {
		assertThat(new HttpHealthProbe(RestClient.builder()).check("http://bad host/").outcome())
			.isEqualTo(Outcome.INTERNAL_ERROR);
	}

}
