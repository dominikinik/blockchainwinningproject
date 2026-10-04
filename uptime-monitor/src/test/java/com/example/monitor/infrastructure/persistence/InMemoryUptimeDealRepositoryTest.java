package com.example.monitor.infrastructure.persistence;

import com.example.monitor.domain.deal.UptimeDealRepository;
import com.example.monitor.support.InMemoryUptimeDealRepository;

class InMemoryUptimeDealRepositoryTest extends UptimeDealRepositoryContract {

	@Override
	UptimeDealRepository newRepository() {
		return new InMemoryUptimeDealRepository();
	}

}
