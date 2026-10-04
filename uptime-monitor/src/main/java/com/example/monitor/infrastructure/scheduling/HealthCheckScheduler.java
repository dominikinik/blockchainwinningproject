package com.example.monitor.infrastructure.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.monitor.application.HealthRelay;

/** Relays the provider's health to the chain every {@code monitor.check-interval-ms}. */
@Component
@ConditionalOnProperty(name = "monitor.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class HealthCheckScheduler {

	private final HealthRelay relay;

	public HealthCheckScheduler(HealthRelay relay) {
		this.relay = relay;
	}

	@Scheduled(fixedRateString = "${monitor.check-interval-ms}", initialDelayString = "${monitor.check-interval-ms}")
	public void relay() {
		relay.relay();
	}

}
