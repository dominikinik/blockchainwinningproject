package com.example.monitor.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthProbe;

/**
 * The proxy loop: calls the provider's health endpoint once, records the result in the in-memory
 * {@link UptimeHistory}, and hands it to every {@link HealthListener}; the deal oracle ({@link DealService}) sends it
 * on chain as the UP/DOWN observation of each registered deal's round that just ended.
 */
public class HealthRelay {

	private static final Logger log = LoggerFactory.getLogger(HealthRelay.class);

	/**
	 * What one relay observed.
	 *
	 * @param observedAt when the health call started; deal rounds are computed from it
	 * @param result     the classified health response
	 */
	public record Relay(Instant observedAt, HealthCheckResult result) {

		public boolean up() {
			return result.outcome() == HealthCheckResult.Outcome.HEALTHY;
		}

	}

	private final HealthProbe probe;

	private final String healthUrl;

	private final List<HealthListener> listeners;

	private final UptimeHistory history;

	private final Clock clock;

	/** @param listeners who gets each result; empty when the blockchain is off and results are only logged */
	public HealthRelay(HealthProbe probe, String healthUrl, List<HealthListener> listeners, UptimeHistory history,
			Clock clock) {
		this.probe = probe;
		this.healthUrl = healthUrl;
		this.listeners = List.copyOf(listeners);
		this.history = history;
		this.clock = clock;
	}

	/** Probes once and forwards the result. Never throws: a listener's failure is logged. */
	public Relay relay() {
		Relay relay = new Relay(clock.instant(), probe.check(healthUrl));
		history.record(relay.observedAt(), relay.up());
		if (!relay.up()) {
			log.info("{} is DOWN: {}", healthUrl, relay.result().detail());
		}
		for (HealthListener listener : listeners) {
			try {
				listener.onHealthResult(relay.observedAt(), relay.up());
			}
			catch (RuntimeException e) {
				log.warn("Relaying the health result failed: {}", e.getMessage());
			}
		}
		return relay;
	}

}
