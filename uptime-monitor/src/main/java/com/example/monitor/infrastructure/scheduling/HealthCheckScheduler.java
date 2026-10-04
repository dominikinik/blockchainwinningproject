package com.example.monitor.infrastructure.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.monitor.application.TrackingService;

/** Calls every tracked service's health endpoint every {@code monitor.check-interval-ms}. */
@Component
@ConditionalOnProperty(name = "monitor.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class HealthCheckScheduler {

	private final TrackingService tracking;

	public HealthCheckScheduler(TrackingService tracking) {
		this.tracking = tracking;
	}

	@Scheduled(fixedRateString = "${monitor.check-interval-ms}", initialDelayString = "${monitor.check-interval-ms}")
	public void checkAll() {
		tracking.checkActive();
	}

}
