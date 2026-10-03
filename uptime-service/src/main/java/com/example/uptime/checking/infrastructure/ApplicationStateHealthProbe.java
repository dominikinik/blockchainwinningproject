package com.example.uptime.checking.infrastructure;

import java.util.Objects;

import org.springframework.boot.health.contributor.Status;

import com.example.uptime.checking.application.HealthProbe;
import com.example.uptime.checking.domain.ProbeResult;
import com.example.uptime.checking.domain.FailureType;
import com.example.uptime.state.ApplicationStateHealthIndicator;

public class ApplicationStateHealthProbe implements HealthProbe {
	private final ApplicationStateHealthIndicator indicator;

	public ApplicationStateHealthProbe(ApplicationStateHealthIndicator indicator) {
		this.indicator = Objects.requireNonNull(indicator, "indicator");
	}

	@Override
	public ProbeResult check() {
		return Status.UP.equals(indicator.health().getStatus())
				? ProbeResult.success()
				: ProbeResult.failure("HEALTH_DOWN", "Application health is not UP", FailureType.DOWNTIME, "application");
	}
}
