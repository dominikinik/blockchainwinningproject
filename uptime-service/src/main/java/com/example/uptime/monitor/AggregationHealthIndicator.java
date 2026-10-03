package com.example.uptime.monitor;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.example.uptime.aggregation.application.AggregateChecks;
import com.example.uptime.aggregation.application.AggregationStatus;
import com.example.uptime.tracking.application.TrackingService;

@Component
public class AggregationHealthIndicator implements HealthIndicator {
	private final AggregateChecks aggregation;
	private final TrackingService tracking;

	public AggregationHealthIndicator(AggregateChecks aggregation, TrackingService tracking) {
		this.aggregation = aggregation;
		this.tracking = tracking;
	}

	@Override
	public Health health() {
		AggregationStatus status = aggregation.status();
		// Rejected observations are unrecoverable: keep that signal visible until restart.
		String lifecycleFailure = tracking.lifecycleFailure();
				Health.Builder health = status.consecutivePersistenceFailures() > 0 || status.rejectedChecks() > 0
						|| lifecycleFailure != null
				? Health.down() : Health.up();
		health.withDetail("openWindows", status.openWindows())
				.withDetail("pendingEvents", status.pendingEvents())
				.withDetail("rejectedChecks", status.rejectedChecks())
				.withDetail("lateChecks", status.lateChecks())
								.withDetail("outOfOrderChecks", status.outOfOrderChecks())
								.withDetail("capacityRejections", status.capacityRejections())
								.withDetail("activeSession", status.activeSession())
				.withDetail("consecutivePersistenceFailures", status.consecutivePersistenceFailures());
		if (status.nextRetryAt() != null) {
			health.withDetail("nextRetryAt", status.nextRetryAt());
		}
		if (status.lastPersistenceFailure() != null) {
			health.withDetail("lastPersistenceFailure", status.lastPersistenceFailure());
		}
		if (lifecycleFailure != null) {
			health.withDetail("lifecycleFailure", lifecycleFailure);
		}
		return health.build();
	}
}
