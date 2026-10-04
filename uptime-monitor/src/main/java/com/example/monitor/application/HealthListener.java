package com.example.monitor.application;

import java.time.Instant;

/** Receives every health result that {@link HealthRelay} relays. */
@FunctionalInterface
public interface HealthListener {

	/**
	 * @param observedAt when the health call started
	 * @param up         whether the provider was healthy
	 */
	void onHealthResult(Instant observedAt, boolean up);

}
