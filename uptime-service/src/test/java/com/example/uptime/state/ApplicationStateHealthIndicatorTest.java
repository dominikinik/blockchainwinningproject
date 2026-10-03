package com.example.uptime.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class ApplicationStateHealthIndicatorTest {

	private final ApplicationStateService state = new ApplicationStateService();

	private final ApplicationStateHealthIndicator indicator = new ApplicationStateHealthIndicator(state);

	@Test
	void upWithoutDetailsWhileRunning() {
		Health health = indicator.health();
		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).isEmpty();
	}

	@Test
	void downWithReasonWhenStopped() {
		state.stop();
		Health health = indicator.health();
		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(health.getDetails()).containsEntry("reason", "stopped via API");
	}

	@Test
	void followsStateBackUp() {
		state.stop();
		state.start();
		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

}
