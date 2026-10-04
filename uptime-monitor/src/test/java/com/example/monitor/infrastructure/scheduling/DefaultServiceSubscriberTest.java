package com.example.monitor.infrastructure.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.example.monitor.application.DealService;
import com.example.monitor.application.TrackingService;
import com.example.monitor.domain.ServiceId;

class DefaultServiceSubscriberTest {

	final TrackingService tracking = mock(TrackingService.class);

	final ServiceId id = ServiceId.newId();

	@Test
	void subscribesTheDefaultServiceWhenItIsNotTracked() {
		new DefaultServiceSubscriber(tracking, id, "http://p/api/health").subscribe();
		verify(tracking).subscribe("http://p/api/health", id);
	}

	@Test
	void leavesAnActiveDefaultServiceAlone() {
		when(tracking.isActive(id)).thenReturn(true);
		new DefaultServiceSubscriber(tracking, id, "http://p/api/health").subscribe();
		verify(tracking, never()).subscribe(anyString(), any());
	}

	@Test
	void doesNothingWithoutADefaultService() {
		new DefaultServiceSubscriber(tracking, null, "").subscribe();
		verifyNoInteractions(tracking);
	}

	@Test
	void theDealSchedulerAdvancesDeals() {
		DealService deals = mock(DealService.class);
		new DealSettlementScheduler(deals).settleDue();
		verify(deals).settleDue();
	}

}
