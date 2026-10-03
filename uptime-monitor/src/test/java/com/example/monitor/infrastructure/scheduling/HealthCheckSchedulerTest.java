package com.example.monitor.infrastructure.scheduling;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.example.monitor.application.TrackingService;

class HealthCheckSchedulerTest {

	@Test
	void eachTickChecksEveryTrackedService() {
		TrackingService tracking = mock(TrackingService.class);
		new HealthCheckScheduler(tracking).checkAll();
		verify(tracking).checkActive();
	}

}
