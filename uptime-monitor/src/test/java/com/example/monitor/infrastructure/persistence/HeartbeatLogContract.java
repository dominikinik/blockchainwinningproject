package com.example.monitor.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.heartbeat.Heartbeat;
import com.example.monitor.domain.heartbeat.HeartbeatLog;

/** Behaviour every {@link HeartbeatLog} must have. */
abstract class HeartbeatLogContract {

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00.123456Z");

	HeartbeatLog log;

	abstract HeartbeatLog newLog();

	@BeforeEach
	void create() {
		log = newLog();
	}

	static Heartbeat beat(String deal, int round, Instant at, HealthCheckResult result) {
		return Heartbeat.probed(deal, round, at, result, 42);
	}

	@Test
	void appendAssignsIdsAndRoundTripsEveryField() {
		Heartbeat up = log.append(beat("D1", 0, T0, HealthCheckResult.fromResponse(200, "UP")).sent("sig"));
		Heartbeat down = log.append(beat("D1", 1, T0.plusSeconds(2), HealthCheckResult.unreachable("refused"))
			.retrying("node down"));

		assertThat(up.id()).isNotNull();
		assertThat(down.id()).isGreaterThan(up.id());
		assertThat(log.recent(10)).containsExactly(down, up);
	}

	@Test
	void updateReplacesTheReportOnly() {
		Heartbeat stored = log.append(beat("D1", 0, T0, HealthCheckResult.fromResponse(404, null)).retrying("x"));

		log.update(stored.sent("sig"));
		assertThat(log.recent(1)).containsExactly(stored.sent("sig"));
		log.update(stored.dropped("closed"));
		assertThat(log.recent(1)).containsExactly(stored.dropped("closed"));
	}

	@Test
	void updatingAnUnknownHeartbeatFails() {
		Heartbeat ghost = beat("D1", 0, T0, HealthCheckResult.fromResponse(200, "UP")).withId(999);
		assertThatThrownBy(() -> log.update(ghost)).isInstanceOf(NoSuchElementException.class);
	}

	@Test
	void readsAreNewestFirstLimitedAndFilteredByDeal() {
		HealthCheckResult up = HealthCheckResult.fromResponse(200, "UP");
		Heartbeat a0 = log.append(beat("A", 0, T0, up).sent("a0"));
		Heartbeat b0 = log.append(beat("B", 0, T0.plusSeconds(1), up).sent("b0"));
		Heartbeat a1 = log.append(beat("A", 1, T0.plusSeconds(2), up).sent("a1"));
		Heartbeat sameTime = log.append(beat("B", 1, T0.plusSeconds(2), up).sent("b1"));

		assertThat(log.recent(10)).containsExactly(sameTime, a1, b0, a0);
		assertThat(log.recent(2)).containsExactly(sameTime, a1);
		assertThat(log.forDeal("A", 10)).containsExactly(a1, a0);
		assertThat(log.forDeal("A", 1)).containsExactly(a1);
		assertThat(log.forDeal("nope", 10)).isEmpty();
	}

	@Test
	void anEmptyLogReadsEmpty() {
		assertThat(log.recent(50)).isEmpty();
	}

}
