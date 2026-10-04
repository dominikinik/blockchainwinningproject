package com.example.monitor.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthProbe;

/**
 * The dashboard's sampler: calls the provider's health endpoint every {@code monitor.check-interval-ms} and records
 * the result in the in-memory {@link UptimeHistory} behind {@code /api/uptime}. Deals don't use it: {@link DealService}
 * checks the provider once per round of each deal's own interval.
 */
public class HealthRelay {

	private static final Logger log = LoggerFactory.getLogger(HealthRelay.class);

	/**
	 * What one relay observed.
	 *
	 * @param observedAt when the health call started
	 * @param result     the classified health response
	 */
	public record Relay(Instant observedAt, HealthCheckResult result) {

		public boolean up() {
			return result.outcome() == HealthCheckResult.Outcome.HEALTHY;
		}

	}

	private final HealthProbe probe;

	private final String healthUrl;

	private final UptimeHistory history;

	private final Clock clock;

	public HealthRelay(HealthProbe probe, String healthUrl, UptimeHistory history, Clock clock) {
		this.probe = probe;
		this.healthUrl = healthUrl;
		this.history = history;
		this.clock = clock;
	}

	/** Probes once and records the result. Never throws: the probe reports failures as results. */
	public Relay relay() {
		Relay relay = new Relay(clock.instant(), probe.check(healthUrl));
		history.record(relay.observedAt(), relay.up());
		if (!relay.up()) {
			log.info("{} is DOWN: {}", healthUrl, relay.result().detail());
		}
		return relay;
	}

}
