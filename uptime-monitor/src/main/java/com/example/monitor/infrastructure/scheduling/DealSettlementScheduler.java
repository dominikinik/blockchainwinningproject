package com.example.monitor.infrastructure.scheduling;

import org.springframework.scheduling.annotation.Scheduled;

import com.example.monitor.application.DealService;

/** Advances deal settlements every {@code monitor.deal.poll-interval-ms}. Registered by {@code MonitorConfig}. */
public class DealSettlementScheduler {

	private final DealService deals;

	public DealSettlementScheduler(DealService deals) {
		this.deals = deals;
	}

	@Scheduled(fixedDelayString = "${monitor.deal.poll-interval-ms}")
	public void settleDue() {
		deals.settleDue();
	}

}
