package com.example.monitor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.monitor.domain.HealthCheckResult.Outcome;

class HealthCheckResultTest {

	@Test
	void okWithUpStatusIsHealthy() {
		assertThat(HealthCheckResult.fromResponse(200, "UP").outcome()).isEqualTo(Outcome.HEALTHY);
	}

	@Test
	void okWithoutStatusIsHealthy() {
		assertThat(HealthCheckResult.fromResponse(200, null).outcome()).isEqualTo(Outcome.HEALTHY);
		assertThat(HealthCheckResult.fromResponse(200, "UNKNOWN").outcome()).isEqualTo(Outcome.HEALTHY);
	}

	@ParameterizedTest
	@ValueSource(strings = { "DOWN", "down", " Down ", "OUT_OF_SERVICE" })
	void okReportingDownIsDowntime(String status) {
		HealthCheckResult result = HealthCheckResult.fromResponse(200, status);
		assertThat(result.outcome()).isEqualTo(Outcome.DOWN);
		assertThat(result.httpStatus()).isEqualTo(200);
	}

	@Test
	void notFoundIsDowntime() {
		HealthCheckResult result = HealthCheckResult.fromResponse(404, null);
		assertThat(result.outcome()).isEqualTo(Outcome.DOWN);
		assertThat(result.httpStatus()).isEqualTo(404);
	}

	@ParameterizedTest
	@ValueSource(ints = { 201, 204, 301, 400, 401, 403, 405, 429, 500, 502, 503, 504 })
	void anyOtherStatusIsInternalError(int status) {
		HealthCheckResult result = HealthCheckResult.fromResponse(status, "DOWN");
		assertThat(result.outcome()).isEqualTo(Outcome.INTERNAL_ERROR);
		assertThat(result.httpStatus()).isEqualTo(status);
	}

	@Test
	void noResponseIsInternalErrorWithoutStatus() {
		HealthCheckResult result = HealthCheckResult.unreachable("Connection refused");
		assertThat(result.outcome()).isEqualTo(Outcome.INTERNAL_ERROR);
		assertThat(result.httpStatus()).isNull();
		assertThat(result.detail()).isEqualTo("Connection refused");
	}

}
