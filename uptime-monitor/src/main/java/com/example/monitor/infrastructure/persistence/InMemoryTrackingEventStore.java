package com.example.monitor.infrastructure.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEventStore;

/** {@link TrackingEventStore} in memory: events are lost on restart. */
public class InMemoryTrackingEventStore implements TrackingEventStore {

	private final Map<ServiceId, List<TrackingEvent>> streams = new LinkedHashMap<>();

	@Override
	public synchronized void append(ServiceId id, long expectedVersion, List<TrackingEvent> events) {
		List<TrackingEvent> stream = streams.getOrDefault(id, List.of());
		if (stream.size() != expectedVersion) {
			throw new ConcurrencyException(id, expectedVersion, stream.size());
		}
		for (TrackingEvent event : events) {
			if (!event.serviceId().equals(id)) {
				throw new IllegalArgumentException("Event of " + event.serviceId() + " appended to " + id);
			}
		}
		if (events.isEmpty()) {
			return;
		}
		streams.computeIfAbsent(id, k -> new ArrayList<>()).addAll(events);
	}

	@Override
	public synchronized List<TrackingEvent> load(ServiceId id) {
		return List.copyOf(streams.getOrDefault(id, List.of()));
	}

	@Override
	public synchronized List<ServiceId> serviceIds() {
		return List.copyOf(streams.keySet());
	}

}
