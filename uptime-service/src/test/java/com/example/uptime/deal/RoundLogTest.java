package com.example.uptime.deal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RoundLogTest {

	private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

	private final RoundLog log = new RoundLog();

	@Test
	void aRoundIsUpOnlyIfEverySampleWasUp() {
		log.witness(0, true);
		log.witness(0, true);
		log.witness(1, true);
		log.witness(1, false);
		log.witness(1, true);
		log.witness(3, false);

		assertThat(log.snapshot()).containsExactly(Map.entry(0, true), Map.entry(1, false), Map.entry(3, false));
	}

	@Test
	void snapshotsAreCopies() {
		log.witness(0, true);
		Map<Integer, Boolean> snapshot = log.snapshot();
		log.witness(0, false);
		assertThat(snapshot).containsEntry(0, true);
	}

	@Test
	void aRoundIsDueUntilSentThenAgainAfterTheRetryDelay() {
		Duration retry = Duration.ofSeconds(2);
		assertThat(log.due(0, T0, retry)).isTrue();
		log.markSent(0, T0);
		assertThat(log.due(0, T0.plusMillis(1_999), retry)).isFalse();
		assertThat(log.due(0, T0.plusSeconds(2), retry)).isTrue();
		assertThat(log.due(1, T0, retry)).isTrue();
	}

	@Test
	void forgettingARoundDropsItsResultAndSendTime() {
		log.witness(0, true);
		log.markSent(0, T0);
		log.forget(0);
		assertThat(log.snapshot()).isEmpty();
		assertThat(log.due(0, T0, Duration.ofHours(1))).isTrue();
	}

}
