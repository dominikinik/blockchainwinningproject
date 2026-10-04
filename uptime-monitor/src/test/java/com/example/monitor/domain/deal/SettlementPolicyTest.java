package com.example.monitor.domain.deal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;

class SettlementPolicyTest {

	static final ServiceId S = ServiceId.newId();

	static final Instant T = Instant.parse("2026-10-04T12:00:00Z");

	static final Downtime DOWN = new Downtime(S, 404, "", T);

	static final InternalErrorHappened ERROR = new InternalErrorHappened(S, null, "", T);

	static final TrackingFinished FINISHED = new TrackingFinished(S, T);

	@Test
	void thresholdIsStrictlyAbove99PercentLikeTheProgram() {
		assertThat(SettlementPolicy.aboveThreshold(991, 1000)).isTrue();
		assertThat(SettlementPolicy.aboveThreshold(990, 1000)).isFalse();
		assertThat(SettlementPolicy.aboveThreshold(1, 1)).isTrue();
		assertThat(SettlementPolicy.aboveThreshold(98, 99)).isFalse();
		assertThat(SettlementPolicy.aboveThreshold(Long.MAX_VALUE, Long.MAX_VALUE)).isTrue();
		assertThat(SettlementPolicy.aboveThreshold(Long.MAX_VALUE / 100 * 99, Long.MAX_VALUE)).isFalse();
	}

	@Test
	void aFailureThatMakes99PercentUnreachableClosesWithTheBestCase() {
		DealMeasurement m = new DealMeasurement(10, 4, 2, 2, 6);
		assertThat(SettlementPolicy.onEvent(DOWN, m)).contains(new Verdict(8, 10));
		assertThat(SettlementPolicy.onEvent(ERROR, m)).contains(new Verdict(8, 10));
		assertThat(new Verdict(8, 10).paysRecipient()).isFalse();
	}

	@Test
	void aFailureThatLeaves99PercentReachableKeepsTheDealOpen() {
		DealMeasurement m = new DealMeasurement(1000, 100, 2, 98, 900);
		assertThat(SettlementPolicy.onEvent(DOWN, m)).isEmpty();
		assertThat(SettlementPolicy.onEvent(ERROR, new DealMeasurement(10, 4, 0, 4, 6))).isEmpty();
	}

	@Test
	void exactly99PercentBestCaseClosesTheDeal() {
		assertThat(SettlementPolicy.onEvent(DOWN, new DealMeasurement(1000, 500, 10, 490, 500)))
			.contains(new Verdict(990, 1000));
		assertThat(SettlementPolicy.onEvent(DOWN, new DealMeasurement(1000, 500, 9, 491, 500))).isEmpty();
	}

	@Test
	void trackingFinishedSettlesWithTheUpSecondsSoFar() {
		assertThat(SettlementPolicy.onEvent(FINISHED, new DealMeasurement(10, 4, 0, 4, 6))).contains(new Verdict(4, 10));
		assertThat(SettlementPolicy.onEvent(FINISHED, new DealMeasurement(10, 10, 0, 10, 0)))
			.contains(new Verdict(10, 10));
	}

	@Test
	void otherEventsNeverDecide() {
		assertThat(SettlementPolicy.onEvent(new TrackingStarted(S, "http://x", Duration.ofSeconds(2), T),
				new DealMeasurement(10, 0, 0, 0, 0))).isEmpty();
	}

	@Test
	void windowEndSettlesWithTheMeasuredUptime() {
		assertThat(SettlementPolicy.atWindowEnd(new DealMeasurement(10, 10, 0, 10, 0))).isEqualTo(new Verdict(10, 10));
		assertThat(SettlementPolicy.atWindowEnd(new DealMeasurement(10, 10, 4, 6, 0)).paysRecipient()).isFalse();
	}

	@Test
	void verdictsMustBeValidForTheProgram() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Verdict(0, 0));
		assertThatIllegalArgumentException().isThrownBy(() -> new Verdict(11, 10));
		assertThatIllegalArgumentException().isThrownBy(() -> new Verdict(-1, 10));
		assertThat(new Verdict(0, 10).paysRecipient()).isFalse();
	}

}
