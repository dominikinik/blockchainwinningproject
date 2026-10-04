package com.example.monitor.domain;

/** Port: calls a service's health endpoint once. */
public interface HealthProbe {

	/**
	 * Checks a health endpoint. Never throws for a failed call: failures are results too.
	 *
	 * @param healthUrl absolute http(s) URL of the endpoint
	 */
	HealthCheckResult check(String healthUrl);

}
