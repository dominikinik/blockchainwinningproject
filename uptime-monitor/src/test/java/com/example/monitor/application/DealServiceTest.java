package com.example.monitor.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.HealthCheckSucceeded;
import com.example.monitor.domain.TrackingEventStore;
import com.example.monitor.domain.deal.DealChain;
import com.example.monitor.domain.deal.DealChain.ChainDeal;
import com.example.monitor.support.InMemoryTrackingEventStore;
import com.example.monitor.support.InMemoryUptimeDealRepository;

class DealServiceTest {

	private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");

	@Test
	void reportsCompletedRoundsAndLeavesPayoutDecisionToTheContract() {
		DealChain chain = mock(DealChain.class);
		TrackingEventStore events = new InMemoryTrackingEventStore();
		ServiceId service = ServiceId.newId();
		TrackingEvent.TrackingStarted started = new TrackingEvent.TrackingStarted(service, "http://provider/health",
				Duration.ofSeconds(1), START.minusSeconds(10));
		events.append(service, 0, List.of(started));
		byte[] recorded = new byte[] { 0 };
		ChainDeal account = new ChainDeal("payer", "recipient", "oracle", 500, 100, 3, 1, 9_000, 3, 0, 0,
				recorded, START.plusSeconds(100), START);
		when(chain.programId()).thenReturn("program");
		when(chain.oracleAddress()).thenReturn("oracle");
		when(chain.rpcUrl()).thenReturn("http://rpc");
		when(chain.readDeal("deal")).thenReturn(account);
		DealService serviceUnderTest = new DealService(new InMemoryUptimeDealRepository(), chain, events,
				Clock.fixed(START.plusSeconds(1), ZoneOffset.UTC), new DealService.Settings(service, 100, 0, 3, 30, 1));

		serviceUnderTest.register("deal", service);
		serviceUnderTest.onTrackingEvent(new HealthCheckSucceeded(service, START.plusSeconds(1)));

		verify(chain).recordObservation("deal", account, 0, true);
		verify(chain, never()).sendSettle(any(), any());
	}

	@Test
	void doesNotSubmitASettlementUntilTheContractReportsAnEarlyBreachOrWindowExpiry() {
		DealChain chain = mock(DealChain.class);
		TrackingEventStore events = new InMemoryTrackingEventStore();
		ServiceId service = ServiceId.newId();
		events.append(service, 0, List.of(new TrackingEvent.TrackingStarted(service, "http://provider/health",
				Duration.ofSeconds(1), START.minusSeconds(10))));
		ChainDeal account = new ChainDeal("payer", "recipient", "oracle", 500, 100, 3, 1, 9_000, 3, 0, 0,
				new byte[] { 0 }, START.plusSeconds(100), START);
		when(chain.programId()).thenReturn("program");
		when(chain.oracleAddress()).thenReturn("oracle");
		when(chain.rpcUrl()).thenReturn("http://rpc");
		when(chain.readDeal("deal")).thenReturn(account);
		DealService serviceUnderTest = new DealService(new InMemoryUptimeDealRepository(), chain, events,
				Clock.fixed(START.plusMillis(500), ZoneOffset.UTC), new DealService.Settings(service, 100, 0, 3, 30, 1));

		serviceUnderTest.register("deal", service);
		serviceUnderTest.settleDue();

		verify(chain, never()).sendSettle(any(), any());
	}
}
