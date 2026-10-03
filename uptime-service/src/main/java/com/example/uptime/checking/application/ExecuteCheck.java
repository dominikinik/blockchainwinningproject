package com.example.uptime.checking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.example.uptime.checking.domain.CheckResult;
import com.example.uptime.checking.domain.ProbeResult;

public class ExecuteCheck {
	private final HealthProbe probe;
	private final CheckResultSink sink;
	private final Clock clock;

	public ExecuteCheck(HealthProbe probe, CheckResultSink sink, Clock clock) {
		this.probe = Objects.requireNonNull(probe, "probe");
		this.sink = Objects.requireNonNull(sink, "sink");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public CheckResult execute() {
		UUID checkId = UUID.randomUUID();
		Instant startedAt = clock.instant();
		long startNanos = System.nanoTime();
		ProbeResult probeResult;
		try {
			probeResult = Objects.requireNonNull(probe.check(), "probe result");
		}
		catch (RuntimeException exception) {
			// Never expose exception messages, class names or causes: they may contain secrets.
			probeResult = ProbeResult.failure("PROBE_EXCEPTION", "Health probe failed unexpectedly");
		}
		long durationNanos = Math.max(0L, System.nanoTime() - startNanos);
		Instant observedAt = clock.instant();
		// Wall clocks can move backwards; elapsed time remains independently monotonic.
		if (observedAt.isBefore(startedAt)) {
			observedAt = startedAt;
		}
		CheckResult result = new CheckResult(checkId, startedAt, observedAt, durationNanos,
				probeResult.outcome(), probeResult.failure());
		sink.accept(result);
		return result;
	}
}
