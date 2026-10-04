package com.example.monitor.infrastructure.persistence;

import com.example.monitor.domain.TrackingEventStore;
import com.example.monitor.support.InMemoryTrackingEventStore;

/** Keeps the test fake honest: it must behave like the real store. */
class InMemoryTrackingEventStoreTest extends TrackingEventStoreContract {

	@Override
	TrackingEventStore newStore() {
		return new InMemoryTrackingEventStore();
	}

}
