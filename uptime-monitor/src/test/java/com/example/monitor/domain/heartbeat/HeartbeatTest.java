package com.example.monitor.domain.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthCheckResult.Outcome;
import com.example.monitor.domain.heartbeat.Heartbeat.Report;

class HeartbeatTest {

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

	@Test
	void aProbedHeartbeatCarriesTheResultAndAwaitsItsReport() {
		Heartbeat h = Heartbeat.probed("deal", 3, T0, HealthCheckResult.fromResponse(200, "UP"), 12);

		assertThat(h.id()).isNull();
		assertThat(h.up()).isTrue();
		assertThat(h.httpStatus()).isEqualTo(200);
		assertThat(h.detail()).isEqualTo("HTTP 200, status UP");
		assertThat(h.report()).isEqualTo(Report.RETRYING);
		assertThat(h.signature()).isNull();
	}

	@Test
	void onlyAHealthyProviderIsUp() {
		assertThat(Heartbeat.probed("d", 0, T0, HealthCheckResult.fromResponse(404, null), 0).up()).isFalse();
		Heartbeat unreachable = Heartbeat.probed("d", 0, T0, HealthCheckResult.unreachable("refused"), 0);
		assertThat(unreachable.up()).isFalse();
		assertThat(unreachable.outcome()).isEqualTo(Outcome.INTERNAL_ERROR);
		assertThat(unreachable.httpStatus()).isNull();
	}

	@Test
	void reportTransitionsKeepTheProbeAndReplaceTheDelivery() {
		Heartbeat h = Heartbeat.probed("deal", 1, T0, HealthCheckResult.fromResponse(200, "DOWN"), 5).withId(7);

		Heartbeat retrying = h.retrying("node down");
		assertThat(retrying.report()).isEqualTo(Report.RETRYING);
		assertThat(retrying.reportError()).isEqualTo("node down");

		Heartbeat sent = retrying.sent("sig");
		assertThat(sent).isEqualTo(new Heartbeat(7L, "deal", 1, T0, Outcome.DOWN, 200, "HTTP 200, status DOWN", 5,
				Report.SENT, null, "sig"));

		assertThat(retrying.dropped("closed").report()).isEqualTo(Report.DROPPED);
		assertThat(retrying.dropped("closed").reportError()).isEqualTo("closed");
	}

	@Test
	void rejectsInvalidValues() {
		HealthCheckResult up = HealthCheckResult.fromResponse(200, "UP");
		assertThatIllegalArgumentException().isThrownBy(() -> Heartbeat.probed("d", -1, T0, up, 0));
		assertThatIllegalArgumentException().isThrownBy(() -> Heartbeat.probed("d", 0, T0, up, -1));
		assertThatNullPointerException().isThrownBy(() -> Heartbeat.probed(null, 0, T0, up, 0));
		assertThatNullPointerException().isThrownBy(() -> Heartbeat.probed("d", 0, null, up, 0));
		assertThatNullPointerException()
			.isThrownBy(() -> new Heartbeat(null, "d", 0, T0, Outcome.HEALTHY, 200, null, 0, null, null, null));
	}

}
