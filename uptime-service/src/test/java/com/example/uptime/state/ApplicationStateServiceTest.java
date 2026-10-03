package com.example.uptime.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApplicationStateServiceTest {

	private final ApplicationStateService state = new ApplicationStateService();

	@Test
	void startsUp() {
		assertThat(state.isUp()).isTrue();
	}

	@Test
	void stopAndStartToggleState() {
		state.stop();
		assertThat(state.isUp()).isFalse();
		state.start();
		assertThat(state.isUp()).isTrue();
	}

	@Test
	void repeatedCallsAreIdempotent() {
		state.stop();
		state.stop();
		assertThat(state.isUp()).isFalse();
		state.start();
		state.start();
		assertThat(state.isUp()).isTrue();
	}

}
