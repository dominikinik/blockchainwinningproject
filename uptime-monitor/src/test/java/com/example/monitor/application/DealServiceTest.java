package com.example.monitor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthCheckResult.Outcome;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.deal.DealChain;
import com.example.monitor.domain.deal.DealChain.ChainDeal;
import com.example.monitor.domain.deal.UptimeDeal;
import com.example.monitor.domain.heartbeat.Heartbeat;
import com.example.monitor.domain.heartbeat.Heartbeat.Report;
import com.example.monitor.domain.heartbeat.HeartbeatLog;
import com.example.monitor.support.InMemoryHeartbeatLog;
import com.example.monitor.support.InMemoryUptimeDealRepository;
import com.example.monitor.support.MutableClock;

class DealServiceTest {

	private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");

	private static final String URL = "http://provider.test/api/health";

	private final DealChain chain = mock(DealChain.class);

	private final ServiceId service = ServiceId.newId();

	private final MutableClock clock = new MutableClock(START);

	private final List<String> probes = new ArrayList<>();

	private HealthCheckResult health = HealthCheckResult.fromResponse(200, "UP");

	private final InMemoryHeartbeatLog heartbeats = new InMemoryHeartbeatLog();

	private final DealService deals = new DealService(new InMemoryUptimeDealRepository(), chain, url -> {
		probes.add(url);
		return health;
	}, heartbeats, clock, new DealService.Settings(service, URL, 3_600, 0, 3, 30, 2));

	@BeforeEach
	void setUp() {
		when(chain.programId()).thenReturn("program");
		when(chain.oracleAddress()).thenReturn("oracle");
		when(chain.rpcUrl()).thenReturn("http://rpc");
	}

	/** An accepted deal starting at {@code START}: {@code rounds} rounds of {@code interval} seconds, 90% required. */
	private ChainDeal deal(String address, long interval, int rounds, int... recorded) {
		byte[] bits = new byte[(rounds + 7) / 8];
		for (int round : recorded) {
			bits[round / 8] |= (byte) (1 << (round % 8));
		}
		ChainDeal account = new ChainDeal("payer", "recipient", "oracle", 500, 100, interval * rounds, interval, 9_000,
				rounds, 0, 0, bits, START.plusSeconds(100), START);
		when(chain.readDeal(address)).thenReturn(account);
		return account;
	}

	private void tickAt(Instant at) {
		clock.set(at);
		deals.settleDue();
	}

	@Test
	void theHeartbeatRunsOncePerRoundOfTheDealsOwnInterval() {
		ChainDeal account = deal("deal", 5, 4);
		deals.register("deal", service);

		tickAt(START.plusMillis(4_900));
		assertThat(probes).isEmpty();

		tickAt(START.plusSeconds(5));
		tickAt(START.plusSeconds(7));
		tickAt(START.plusMillis(9_999));
		assertThat(probes).containsExactly(URL);
		verify(chain).recordObservation("deal", account, 0, true);

		tickAt(START.plusMillis(10_200));
		assertThat(probes).hasSize(2);
		verify(chain).recordObservation("deal", account, 1, true);
		verify(chain, never()).sendSettle(any(), any());
	}

	@Test
	void anUnhealthyProviderIsRecordedAsADownRound() {
		ChainDeal account = deal("deal", 3, 4);
		deals.register("deal", null);
		health = HealthCheckResult.unreachable("Connection refused");

		tickAt(START.plusSeconds(3));
		health = HealthCheckResult.fromResponse(200, "DOWN");
		tickAt(START.plusSeconds(6));

		verify(chain).recordObservation("deal", account, 0, false);
		verify(chain).recordObservation("deal", account, 1, false);
	}

	@Test
	void dealsWithDifferentIntervalsShareOneProbePerTick() {
		ChainDeal fast = deal("fast", 1, 10);
		ChainDeal slow = deal("slow", 2, 5);
		deals.register("fast", service);
		deals.register("slow", service);

		tickAt(START.plusSeconds(2));
		assertThat(probes).hasSize(1);
		verify(chain).recordObservation("fast", fast, 1, true);
		verify(chain).recordObservation("slow", slow, 0, true);

		tickAt(START.plusSeconds(3));
		assertThat(probes).hasSize(2);
		verify(chain).recordObservation("fast", fast, 2, true);
		verify(chain, never()).recordObservation("slow", slow, 1, true);
	}

	@Test
	void roundsMissedWhileTheMonitorWasAwayAreNotBackfilled() {
		ChainDeal account = deal("deal", 1, 10);
		deals.register("deal", service);

		tickAt(START.plusSeconds(1));
		tickAt(START.plusSeconds(9));

		verify(chain).recordObservation("deal", account, 0, true);
		verify(chain).recordObservation("deal", account, 8, true);
		verify(chain, times(2)).recordObservation(any(), any(), anyInt(), anyBoolean());
	}

	@Test
	void aRoundAlreadyRecordedOnChainIsNotProbedAgain() {
		deal("deal", 2, 5, 0);
		deals.register("deal", service);

		tickAt(START.plusSeconds(2));

		assertThat(probes).isEmpty();
		verify(chain, never()).recordObservation(any(), any(), anyInt(), anyBoolean());
	}

	@Test
	void aFailedReportIsRetriedOnTheNextTickWithoutProbingAgain() {
		ChainDeal account = deal("deal", 5, 4);
		deals.register("deal", service);
		when(chain.recordObservation("deal", account, 0, true)).thenThrow(new IllegalStateException("node down"))
			.thenReturn("sig");

		tickAt(START.plusSeconds(5));
		tickAt(START.plusMillis(5_500));
		tickAt(START.plusSeconds(6));

		verify(chain, times(2)).recordObservation("deal", account, 0, true);
		assertThat(probes).hasSize(1);
	}

	@Test
	void aRetryIsDroppedOnceTheDealStopsAcceptingObservations() {
		ChainDeal account = deal("deal", 1, 3);
		deals.register("deal", service);
		when(chain.recordObservation("deal", account, 2, true)).thenThrow(new IllegalStateException("node down"));

		tickAt(START.plusSeconds(3));
		tickAt(START.plusSeconds(13));
		tickAt(START.plusSeconds(14));

		verify(chain, times(1)).recordObservation("deal", account, 2, true);
	}

	@Test
	void proposalsAreNotCheckedUntilAccepted() {
		when(chain.readDeal("proposal")).thenReturn(new ChainDeal("payer", "recipient", "oracle", 500, 100, 10, 1, 9_000,
				10, 0, 0, new byte[2], START.plusSeconds(100), null));
		assertThat(deals.register("proposal", service).status()).isEqualTo(UptimeDeal.Status.PROPOSED);

		tickAt(START.plusSeconds(5));

		assertThat(probes).isEmpty();
		verify(chain, never()).recordObservation(any(), any(), anyInt(), anyBoolean());
	}

	@Test
	void doesNotSubmitASettlementUntilTheContractReportsAnEarlyBreachOrWindowExpiry() {
		deal("deal", 1, 3);
		deals.register("deal", service);

		tickAt(START.plusMillis(500));

		verify(chain, never()).sendSettle(any(), any());
	}

	@Test
	void registrationAcceptsAnyRoundIntervalAndIsLinkedToTheCheckedProvider() {
		deal("deal", 7, 3);

		UptimeDeal deal = deals.register("deal", null);

		assertThat(deal.serviceId()).isEqualTo(service);
		assertThat(deal.status()).isEqualTo(UptimeDeal.Status.ACTIVE);
		assertThat(deals.config().checkIntervalSeconds()).isEqualTo(2);
		assertThat(deals.config().oracle()).isEqualTo("oracle");
		assertThatIllegalArgumentException().isThrownBy(() -> deals.register("other", ServiceId.newId()))
			.withMessageContaining("not relayed by this monitor");
		assertThatIllegalArgumentException().isThrownBy(() -> deals.register(" ", null));
	}

	@Test
	void roundEndedByFollowsTheDealsWindow() {
		ChainDeal account = deal("deal", 2, 3);
		assertThat(account.roundEndedBy(START.plusMillis(1_999))).isEqualTo(-1);
		assertThat(account.roundEndedBy(START.plusSeconds(2))).isZero();
		assertThat(account.roundEndedBy(START.plusSeconds(6))).isEqualTo(2);
		assertThat(account.roundEndedBy(START.plusSeconds(8))).isEqualTo(-1);
		assertThat(account.roundEndedBy(START.minusSeconds(3))).isEqualTo(-1);
		ChainDeal proposal = new ChainDeal("p", "r", "o", 1, 1, 4, 2, 1, 2, 0, 0, new byte[1], START, null);
		assertThat(proposal.roundEndedBy(START.plusSeconds(4))).isEqualTo(-1);
	}

	@Test
	void everyHeartbeatIsLoggedWithItsProbeResultAndObservation() {
		ChainDeal account = deal("deal", 2, 5);
		deals.register("deal", service);
		when(chain.recordObservation("deal", account, 0, true)).thenReturn("sig0");
		when(chain.recordObservation("deal", account, 1, false)).thenReturn("sig1");

		tickAt(START.plusSeconds(2));
		health = HealthCheckResult.unreachable("Connection refused");
		tickAt(START.plusSeconds(4));

		assertThat(heartbeats.forDeal("deal", 10)).extracting(Heartbeat::round, Heartbeat::outcome,
				Heartbeat::httpStatus, Heartbeat::report, Heartbeat::signature, Heartbeat::checkedAt)
			.containsExactly(
					tuple(1, Outcome.INTERNAL_ERROR, null, Report.SENT, "sig1", START.plusSeconds(4)),
					tuple(0, Outcome.HEALTHY, 200, Report.SENT, "sig0", START.plusSeconds(2)));
		assertThat(heartbeats.recent(10)).allSatisfy(h -> assertThat(h.latencyMs()).isZero());
	}

	@Test
	void oneSharedProbeIsLoggedOncePerDeal() {
		deal("fast", 1, 10);
		deal("slow", 2, 5);
		deals.register("fast", service);
		deals.register("slow", service);

		tickAt(START.plusSeconds(2));

		assertThat(probes).hasSize(1);
		assertThat(heartbeats.recent(10)).extracting(Heartbeat::dealAddress).containsExactlyInAnyOrder("fast", "slow");
		assertThat(heartbeats.forDeal("unknown", 10)).isEmpty();
	}

	@Test
	void aFailedReportIsLoggedAsRetryingAndUpdatedOnceTheRetryLands() {
		ChainDeal account = deal("deal", 5, 4);
		deals.register("deal", service);
		when(chain.recordObservation("deal", account, 0, true)).thenThrow(new IllegalStateException("node down"))
			.thenThrow(new IllegalStateException("still down"))
			.thenReturn("late");

		tickAt(START.plusSeconds(5));
		assertThat(heartbeats.recent(1)).singleElement()
			.extracting(Heartbeat::report, Heartbeat::reportError)
			.containsExactly(Report.RETRYING, "node down");

		tickAt(START.plusMillis(5_500));
		assertThat(heartbeats.recent(1).getFirst().reportError()).isEqualTo("still down");

		tickAt(START.plusSeconds(6));
		assertThat(heartbeats.recent(10)).singleElement()
			.extracting(Heartbeat::report, Heartbeat::reportError, Heartbeat::signature)
			.containsExactly(Report.SENT, null, "late");
	}

	@Test
	void aDroppedRetryIsLoggedAsDropped() {
		ChainDeal account = deal("deal", 1, 3);
		deals.register("deal", service);
		when(chain.recordObservation("deal", account, 2, true)).thenThrow(new IllegalStateException("node down"));

		tickAt(START.plusSeconds(3));
		tickAt(START.plusSeconds(13));

		assertThat(heartbeats.recent(10)).singleElement()
			.extracting(Heartbeat::report, Heartbeat::reportError)
			.containsExactly(Report.DROPPED, "The deal stopped accepting observations before a retry landed");
	}

	@Test
	void aRetryOfARoundRecordedElsewhereIsLoggedAsDropped() {
		ChainDeal account = deal("deal", 5, 4);
		deals.register("deal", service);
		when(chain.recordObservation("deal", account, 0, true)).thenThrow(new IllegalStateException("timeout"));

		tickAt(START.plusSeconds(5));
		deal("deal", 5, 4, 0);
		tickAt(START.plusSeconds(6));

		assertThat(heartbeats.recent(1).getFirst().report()).isEqualTo(Report.DROPPED);
		assertThat(heartbeats.recent(1).getFirst().reportError()).contains("recorded on chain");
	}

	@Test
	void aBrokenHeartbeatLogNeverStopsTheOracle() {
		HeartbeatLog broken = mock(HeartbeatLog.class);
		when(broken.append(any())).thenThrow(new IllegalStateException("database down"));
		DealService oracle = new DealService(new InMemoryUptimeDealRepository(), chain, url -> health, broken, clock,
				new DealService.Settings(service, URL, 3_600, 0, 3, 30, 2));
		ChainDeal account = deal("deal", 1, 5);
		oracle.register("deal", service);
		when(chain.recordObservation("deal", account, 0, true)).thenThrow(new IllegalStateException("node down"))
			.thenReturn("sig");

		clock.set(START.plusSeconds(1));
		oracle.settleDue();
		clock.set(START.plusSeconds(2));
		oracle.settleDue();

		verify(chain).recordObservation("deal", account, 1, true);
		verify(chain, times(2)).recordObservation("deal", account, 0, true);
		verify(broken, never()).update(any());
	}

}
