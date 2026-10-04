package com.example.monitor.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.monitor.domain.DealChain;
import com.example.monitor.domain.DealChain.ActiveDeal;
import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthProbe;

/**
 * The whole proxy: calls the provider's health endpoint once and forwards the result to every active deal that names
 * this oracle, as the UP/DOWN observation of the round that just ended. Nothing is stored except the in-memory
 * {@link UptimeHistory}; a round whose report fails is not retried (an unobserved round counts as down on chain).
 */
public class HealthRelay {

	private static final Logger log = LoggerFactory.getLogger(HealthRelay.class);

	/** One {@code record_observation} that was sent. */
	public record Observation(String deal, int round, boolean up, String signature) {
	}

	/**
	 * What one relay did.
	 *
	 * @param observedAt when the health call started; rounds are computed from it
	 * @param result     the classified health response
	 * @param sent       the observations sent to the chain
	 */
	public record Relay(Instant observedAt, HealthCheckResult result, List<Observation> sent) {

		public boolean up() {
			return result.outcome() == HealthCheckResult.Outcome.HEALTHY;
		}

	}

	private final HealthProbe probe;

	private final String healthUrl;

	private final DealChain chain;

	private final UptimeHistory history;

	private final Clock clock;

	/** @param chain the program, or {@code null} when the blockchain is off and results are only logged */
	public HealthRelay(HealthProbe probe, String healthUrl, DealChain chain, UptimeHistory history, Clock clock) {
		this.probe = probe;
		this.healthUrl = healthUrl;
		this.chain = chain;
		this.history = history;
		this.clock = clock;
	}

	/** Probes once and reports the result on chain. Never throws: failures are logged. */
	public Relay relay() {
		Instant observedAt = clock.instant();
		HealthCheckResult result = probe.check(healthUrl);
		boolean up = result.outcome() == HealthCheckResult.Outcome.HEALTHY;
		history.record(observedAt, up);
		if (!up) {
			log.info("{} is DOWN: {}", healthUrl, result.detail());
		}
		return new Relay(observedAt, result, chain == null ? List.of() : report(observedAt, up));
	}

	private List<Observation> report(Instant observedAt, boolean up) {
		List<ActiveDeal> deals;
		try {
			deals = chain.activeDeals();
		}
		catch (RuntimeException e) {
			log.warn("Could not list the deals of oracle {}: {}", chain.oracleAddress(), e.getMessage());
			return List.of();
		}
		List<Observation> sent = new ArrayList<>();
		boolean funded = false;
		for (ActiveDeal deal : deals) {
			int round = deal.roundEndedBy(observedAt);
			if (round < 0 || deal.isRecorded(round)) {
				continue;
			}
			if (!funded) {
				chain.ensureOracleFunded();
				funded = true;
			}
			try {
				String signature = chain.recordObservation(deal.address(), round, up);
				sent.add(new Observation(deal.address(), round, up, signature));
				log.info("Recorded deal {} round {} as {}: {}", deal.address(), round, up ? "UP" : "DOWN", signature);
			}
			catch (RuntimeException e) {
				log.warn("Could not record deal {} round {} as {}: {}", deal.address(), round, up ? "UP" : "DOWN",
						e.getMessage());
			}
		}
		return sent;
	}

}
