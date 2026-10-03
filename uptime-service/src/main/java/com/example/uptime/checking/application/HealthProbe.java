package com.example.uptime.checking.application;

import com.example.uptime.checking.domain.ProbeResult;

@FunctionalInterface
public interface HealthProbe {
	ProbeResult check();
}
