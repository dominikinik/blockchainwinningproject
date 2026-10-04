package com.example.monitor.domain;

/** Port: sends a service's downtime to the blockchain. */
public interface DowntimePublisher {

	/**
	 * Publishes one report.
	 *
	 * @throws RuntimeException if it could not be sent; the events stay stored either way
	 */
	void publish(DowntimeReport report);

}
