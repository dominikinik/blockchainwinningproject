package com.example.uptime.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.uptime.aggregation.application.AggregateChecks;
import com.example.uptime.aggregation.application.CheckAdmissionException;
import com.example.uptime.tracking.application.TrackingService;

@Component
@ConditionalOnProperty(name = "uptime.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class MonitoringScheduler {
	private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

	private final TrackingService tracking;
	private final AggregateChecks aggregation;
	private boolean admissionFailureReported;

	public MonitoringScheduler(TrackingService tracking, AggregateChecks aggregation) {
		this.tracking = tracking;
		this.aggregation = aggregation;
	}

	@Scheduled(fixedRateString = "${uptime.sample-interval-ms}")
	public void sample() {
		try {
			tracking.sample();
			admissionFailureReported = false;
		}
		catch (CheckAdmissionException failure) {
			if (!admissionFailureReported) {
				log.error("Uptime check rejected: {}. See aggregation health for rejection counts.", failure.getMessage());
				admissionFailureReported = true;
			}
		}
	}

	@Scheduled(fixedDelayString = "${uptime.flush-interval-ms}")
	public void flush() {
		try {
			tracking.flush();
		}
		catch (RuntimeException failure) {
			log.warn("Tracking lifecycle or uptime events remain pending after {}. Event retry deadline: {}.",
					failure.getClass().getSimpleName(), aggregation.status().nextRetryAt());
		}
	}
}
