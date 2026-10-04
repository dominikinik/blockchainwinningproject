package com.example.monitor.infrastructure.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.example.monitor.application.TrackingService;
import com.example.monitor.domain.ServiceId;

/**
 * Starts tracking the default service ({@code monitor.default-service.*}, the provider) once the monitor is up,
 * unless it is already tracked. Its history and deals linked to it then have data without a manual subscribe.
 */
public class DefaultServiceSubscriber {

	private static final Logger log = LoggerFactory.getLogger(DefaultServiceSubscriber.class);

	private final TrackingService tracking;

	private final ServiceId id;

	private final String healthUrl;

	/** @param id the service's UUID, or {@code null} when no default service is configured (does nothing) */
	public DefaultServiceSubscriber(TrackingService tracking, ServiceId id, String healthUrl) {
		this.tracking = tracking;
		this.id = id;
		this.healthUrl = healthUrl;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void subscribe() {
		if (id == null) {
			return;
		}
		if (tracking.isActive(id)) {
			log.info("Default service {} is already tracked", id);
			return;
		}
		tracking.subscribe(healthUrl, id);
	}

}
