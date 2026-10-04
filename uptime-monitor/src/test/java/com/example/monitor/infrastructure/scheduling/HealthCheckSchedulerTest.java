package com.example.monitor.infrastructure.scheduling;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.example.monitor.application.HealthRelay;

class HealthCheckSchedulerTest {

	@Test
	void eachTickRelaysTheProvidersHealth() {
		HealthRelay relay = mock(HealthRelay.class);
		new HealthCheckScheduler(relay).relay();
		verify(relay).relay();
	}

}
