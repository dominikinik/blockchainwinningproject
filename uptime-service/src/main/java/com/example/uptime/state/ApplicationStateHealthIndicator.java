package com.example.uptime.state;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exposed as the "applicationState" component of /actuator/health. */
@Component
public class ApplicationStateHealthIndicator implements HealthIndicator {

	private final ApplicationStateService state;

	public ApplicationStateHealthIndicator(ApplicationStateService state) {
		this.state = state;
	}

	@Override
	public Health health() {
		return state.isUp() ? Health.up().build() : Health.down().withDetail("reason", "stopped via API").build();
	}

}
