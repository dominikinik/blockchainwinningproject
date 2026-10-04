package com.example.monitor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.deal.DealChain;
import com.example.monitor.domain.deal.DealChain.ChainDeal;
import com.example.monitor.domain.deal.UptimeDeal;
import com.example.monitor.support.InMemoryUptimeDealRepository;

class DealServiceTest {

	private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");

	private final DealChain chain = mock(DealChain.class);

	private final ServiceId service = ServiceId.newId();

	private final ChainDeal account = new ChainDeal("payer", "recipient", "oracle", 500, 100, 3, 1, 9_000, 3, 0, 0,
			new byte[] { 0 }, START.plusSeconds(100), START);

	@BeforeEach
	void setUp() {
		when(chain.programId()).thenReturn("program");
		when(chain.oracleAddress()).thenReturn("oracle");
		when(chain.rpcUrl()).thenReturn("http://rpc");
		when(chain.readDeal("deal")).thenReturn(account);
	}

	private DealService service(Instant now) {
		return new DealService(new InMemoryUptimeDealRepository(), chain, Clock.fixed(now, ZoneOffset.UTC),
				new DealService.Settings(service, 100, 0, 3, 30, 1));
	}

	@Test
	void reportsCompletedRoundsAndLeavesPayoutDecisionToTheContract() {
		DealService serviceUnderTest = service(START.plusSeconds(1));

		serviceUnderTest.register("deal", service);
		serviceUnderTest.onHealthResult(START.plusSeconds(1), true);

		verify(chain).recordObservation("deal", account, 0, true);
		verify(chain, never()).sendSettle(any(), any());
	}

	@Test
	void aFailedHealthCheckIsReportedAsADownRound() {
		DealService serviceUnderTest = service(START.plusSeconds(2));

		serviceUnderTest.register("deal", null);
		serviceUnderTest.onHealthResult(START.plusSeconds(2), false);

		verify(chain).recordObservation("deal", account, 1, false);
	}

	@Test
	void aFailedReportIsRetriedFromMemoryOnTheNextTick() {
		DealService serviceUnderTest = service(START.plusSeconds(1));
		serviceUnderTest.register("deal", service);
		when(chain.recordObservation("deal", account, 0, true)).thenThrow(new IllegalStateException("node down"))
			.thenReturn("sig");

		serviceUnderTest.onHealthResult(START.plusSeconds(1), true);
		serviceUnderTest.settleDue();
		serviceUnderTest.settleDue();

		verify(chain, times(2)).recordObservation("deal", account, 0, true);
	}

	@Test
	void resultsBeforeTheFirstRoundEndsOrForProposalsAreNotReported() {
		DealService serviceUnderTest = service(START.plusMillis(500));
		serviceUnderTest.register("deal", service);
		serviceUnderTest.onHealthResult(START.plusMillis(500), true);

		ChainDeal proposal = new ChainDeal("payer", "recipient", "oracle", 500, 100, 3, 1, 9_000, 3, 0, 0,
				new byte[] { 0 }, START.plusSeconds(100), null);
		when(chain.readDeal("proposal")).thenReturn(proposal);
		DealService onlyProposal = service(START.plusSeconds(2));
		assertThat(onlyProposal.register("proposal", service).status()).isEqualTo(UptimeDeal.Status.PROPOSED);
		onlyProposal.onHealthResult(START.plusSeconds(2), true);

		verify(chain, never()).recordObservation(any(), any(), anyInt(), anyBoolean());
	}

	@Test
	void doesNotSubmitASettlementUntilTheContractReportsAnEarlyBreachOrWindowExpiry() {
		DealService serviceUnderTest = service(START.plusMillis(500));

		serviceUnderTest.register("deal", service);
		serviceUnderTest.settleDue();

		verify(chain, never()).sendSettle(any(), any());
	}

	@Test
	void registrationIsLinkedToTheRelayedServiceAndRejectsOthers() {
		DealService serviceUnderTest = service(START);

		UptimeDeal deal = serviceUnderTest.register("deal", null);

		assertThat(deal.serviceId()).isEqualTo(service);
		assertThat(deal.status()).isEqualTo(UptimeDeal.Status.ACTIVE);
		assertThat(serviceUnderTest.config().oracle()).isEqualTo("oracle");
		assertThatIllegalArgumentException().isThrownBy(() -> serviceUnderTest.register("other", ServiceId.newId()))
			.withMessageContaining("not relayed by this monitor");
		assertThatIllegalArgumentException().isThrownBy(() -> serviceUnderTest.register(" ", null));
	}

}
