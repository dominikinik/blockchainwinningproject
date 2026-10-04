package com.example.monitor.application;

import com.example.monitor.domain.ServiceId;

/**
 * Starts and stops the tracking of a service. {@link DealService} uses it to track each deal's own service from
 * the deal's acceptance until the deal is finished; {@link TrackingService} implements it.
 */
public interface ServiceMonitor {

	/** Starts tracking the service at {@code healthUrl}, unless it is already tracked. */
	void start(ServiceId id, String healthUrl);

	/** Stops tracking the service, if it is tracked. */
	void stop(ServiceId id);

}
