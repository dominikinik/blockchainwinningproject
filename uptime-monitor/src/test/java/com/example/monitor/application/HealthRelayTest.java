package com.example.monitor.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.monitor.application.HealthRelay.Relay;
import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.support.MutableClock;

class HealthRelayTest {

	private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");

	private static final String URL = "http://provider.test/api/health";

	private final MutableClock clock = new MutableClock(START);

	private final UptimeHistory history = new UptimeHistory(clock, Duration.ofSeconds(2), 300, 86_400);

	private final List<String> probed = new ArrayList<>();

	private HealthCheckResult next = HealthCheckResult.fromResponse(200, "UP");

	private final HealthRelay relay = new HealthRelay(url -> {
		probed.add(url);
		return next;
	}, URL, history, clock);

	@Test
	void probesOnceAndRecordsAHealthyResult() {
		clock.set(START.plusSeconds(2));

		Relay result = relay.relay();

		assertThat(probed).containsExactly(URL);
		assertThat(result.up()).isTrue();
		assertThat(result.observedAt()).isEqualTo(START.plusSeconds(2));
		assertThat(history.range(START.plusSeconds(2), START.plusSeconds(3))).noneMatch(UptimeHistory.Point::down);
	}

	@Test
	void downAndUnreachableProvidersAreRecordedAsDown() {
		next = HealthCheckResult.fromResponse(200, "DOWN");
		assertThat(relay.relay().up()).isFalse();
		next = HealthCheckResult.unreachable("Connection refused");
		assertThat(relay.relay().up()).isFalse();
		next = HealthCheckResult.fromResponse(500, null);
		assertThat(relay.relay().up()).isFalse();
	}

	@Test
	void aDownResultMarksItsIntervalDownInTheHistory() {
		next = HealthCheckResult.fromResponse(404, null);
		clock.set(START.plusSeconds(2));

		assertThat(relay.relay().up()).isFalse();
		assertThat(history.range(START.plusSeconds(2), START.plusSeconds(3))).allMatch(UptimeHistory.Point::down);
		assertThat(history.range(START, START.plusSeconds(1))).allMatch(UptimeHistory.Point::down);
	}

}
