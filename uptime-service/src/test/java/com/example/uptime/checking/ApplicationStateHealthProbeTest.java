package com.example.uptime.checking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import com.example.uptime.checking.domain.CheckOutcome;
import com.example.uptime.checking.domain.ProbeResult;
import com.example.uptime.checking.infrastructure.ApplicationStateHealthProbe;
import com.example.uptime.state.ApplicationStateHealthIndicator;
import com.example.uptime.state.ApplicationStateService;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStateHealthProbeTest {
	@Test
	void mapsExistingApplicationStateWithoutSpringContext() {
		ApplicationStateService state = new ApplicationStateService();
		ApplicationStateHealthProbe probe = new ApplicationStateHealthProbe(new ApplicationStateHealthIndicator(state));
		assertEquals(ProbeResult.success(), probe.check());
		state.stop();
		assertEquals("HEALTH_DOWN", probe.check().failure().code());
		state.start();
		assertEquals(ProbeResult.success(), probe.check());
	}

	@Test
	void allNonUpStatusesFailWithoutExposingHealthDetails() {
		for (Status status : new Status[] {Status.DOWN, Status.UNKNOWN, Status.OUT_OF_SERVICE, new Status("CUSTOM")}) {
			ApplicationStateHealthIndicator indicator = new ApplicationStateHealthIndicator(new ApplicationStateService()) {
				@Override
				public Health health() {
					return Health.status(status).withDetail("password", "secret").build();
				}
			};
			ProbeResult result = new ApplicationStateHealthProbe(indicator).check();
			assertEquals(CheckOutcome.FAILURE, result.outcome());
			assertEquals("HEALTH_DOWN", result.failure().code());
			assertEquals(com.example.uptime.checking.domain.FailureType.DOWNTIME, result.failure().type());
			assertEquals("application", result.failure().reason());
			assertEquals("Application health is not UP", result.failure().message());
		}
	}
}
