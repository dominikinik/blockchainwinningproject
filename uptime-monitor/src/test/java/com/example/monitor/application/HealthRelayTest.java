package com.example.monitor.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.monitor.application.HealthRelay.Observation;
import com.example.monitor.application.HealthRelay.Relay;
import com.example.monitor.domain.DealChain;
import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.support.MutableClock;

class HealthRelayTest {

	private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");

	private static final String URL = "http://provider.test/api/health";

	private final MutableClock clock = new MutableClock(START);

	private final FakeChain chain = new FakeChain();

	private final UptimeHistory history = new UptimeHistory(clock, Duration.ofSeconds(2), 300, 86_400);

	private HealthCheckResult next = HealthCheckResult.fromResponse(200, "UP");

	private final List<String> probed = new ArrayList<>();

	private final HealthRelay relay = new HealthRelay(url -> {
		probed.add(url);
		return next;
	}, URL, chain, history, clock);

	@Test
	void aHealthyCheckRecordsTheRoundThatJustEndedAsUp() {
		chain.deals.add(deal("D1", START, 2, 5));
		clock.set(START.plusMillis(4_300));

		Relay result = relay.relay();

		assertThat(probed).containsExactly(URL);
		assertThat(result.up()).isTrue();
		assertThat(result.observedAt()).isEqualTo(START.plusMillis(4_300));
		assertThat(result.sent()).containsExactly(new Observation("D1", 1, true, "sig-D1-1"));
		assertThat(chain.funded).isEqualTo(1);
	}

	@Test
	void aDownOrUnreachableProviderIsRecordedAsDown() {
		chain.deals.add(deal("D1", START, 2, 5));
		clock.set(START.plusSeconds(2));
		next = HealthCheckResult.fromResponse(200, "DOWN");
		assertThat(relay.relay().sent()).containsExactly(new Observation("D1", 0, false, "sig-D1-0"));

		chain.deals.set(0, deal("D1", START, 2, 5, 0));
		clock.set(START.plusSeconds(4));
		next = HealthCheckResult.unreachable("Connection refused");
		assertThat(relay.relay().sent()).containsExactly(new Observation("D1", 1, false, "sig-D1-1"));

		next = HealthCheckResult.fromResponse(500, null);
		chain.deals.set(0, deal("D1", START, 2, 5, 0, 1));
		clock.set(START.plusSeconds(6));
		assertThat(relay.relay().sent()).extracting(Observation::up).containsExactly(false);
	}

	@Test
	void nothingIsSentBeforeTheFirstRoundEndsOrAfterTheLastOne() {
		chain.deals.add(deal("D1", START, 2, 5));
		clock.set(START.plusMillis(1_999));
		assertThat(relay.relay().sent()).isEmpty();

		clock.set(START.plusSeconds(12));
		assertThat(relay.relay().sent()).isEmpty();
		assertThat(chain.funded).isZero();

		clock.set(START.minusSeconds(5));
		assertThat(relay.relay().sent()).isEmpty();
	}

	@Test
	void aRoundAlreadyRecordedOnChainIsSkipped() {
		chain.deals.add(deal("D1", START, 2, 5, 1));
		clock.set(START.plusSeconds(4));
		assertThat(relay.relay().sent()).isEmpty();
		assertThat(chain.sent).isEmpty();
	}

	@Test
	void everyActiveDealGetsItsOwnRoundFromItsOwnWindow() {
		chain.deals.add(deal("D1", START, 2, 5));
		chain.deals.add(deal("D2", START.plusSeconds(1), 1, 10));
		clock.set(START.plusSeconds(6));

		assertThat(relay.relay().sent()).containsExactly(new Observation("D1", 2, true, "sig-D1-2"),
				new Observation("D2", 4, true, "sig-D2-4"));
		assertThat(chain.funded).isEqualTo(1);
	}

	@Test
	void oneDealsFailedSendDoesNotStopTheOthers() {
		chain.deals.add(deal("BAD", START, 2, 5));
		chain.deals.add(deal("D2", START, 2, 5));
		chain.failing = "BAD";
		clock.set(START.plusSeconds(2));

		assertThat(relay.relay().sent()).containsExactly(new Observation("D2", 0, true, "sig-D2-0"));
	}

	@Test
	void anUnreachableChainStillRecordsTheHealthResult() {
		chain.listingFails = true;
		next = HealthCheckResult.fromResponse(404, null);
		clock.set(START.plusSeconds(2));

		Relay result = relay.relay();

		assertThat(result.up()).isFalse();
		assertThat(result.sent()).isEmpty();
		assertThat(history.range(START.plusSeconds(2), START.plusSeconds(3))).allMatch(UptimeHistory.Point::down);
	}

	@Test
	void withoutAChainTheResultIsOnlyRecorded() {
		HealthRelay offline = new HealthRelay(url -> next, URL, null, history, clock);
		clock.set(START.plusSeconds(2));

		assertThat(offline.relay().sent()).isEmpty();
		assertThat(history.range(START.plusSeconds(2), START.plusSeconds(3))).noneMatch(UptimeHistory.Point::down);
	}

	private static DealChain.ActiveDeal deal(String address, Instant startsAt, long interval, int rounds,
			int... recorded) {
		byte[] bits = new byte[(rounds + 7) / 8];
		for (int round : recorded) {
			bits[round / 8] |= (byte) (1 << (round % 8));
		}
		return new DealChain.ActiveDeal(address, startsAt, interval, rounds, bits);
	}

	private static final class FakeChain implements DealChain {

		final List<ActiveDeal> deals = new ArrayList<>();

		final List<String> sent = new ArrayList<>();

		int funded;

		boolean listingFails;

		String failing;

		@Override
		public String programId() {
			return "Prog";
		}

		@Override
		public String oracleAddress() {
			return "Oracle";
		}

		@Override
		public String rpcUrl() {
			return "http://rpc.test";
		}

		@Override
		public List<ActiveDeal> activeDeals() {
			if (listingFails) {
				throw new IllegalStateException("node down");
			}
			return List.copyOf(deals);
		}

		@Override
		public String recordObservation(String address, int round, boolean up) {
			if (address.equals(failing)) {
				throw new IllegalStateException("RoundNotEnded");
			}
			String signature = "sig-" + address + "-" + round;
			sent.add(signature);
			return signature;
		}

		@Override
		public void ensureOracleFunded() {
			funded++;
		}

	}

}
