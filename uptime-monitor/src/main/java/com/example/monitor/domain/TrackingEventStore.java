package com.example.monitor.domain;

import java.util.List;

/** Port: append-only storage of every service's events, one stream per {@link ServiceId}. */
public interface TrackingEventStore {

	/**
	 * Appends events to a service's stream, if nobody else appended since it was read.
	 *
	 * @param expectedVersion how many events the stream held when the caller loaded it
	 * @throws ConcurrencyException if the stream now holds a different number of events
	 */
	void append(ServiceId id, long expectedVersion, List<TrackingEvent> events);

	/** All events of a service in order; empty if it has none. */
	List<TrackingEvent> load(ServiceId id);

	/** Every service that has events, in the order they first appeared. */
	List<ServiceId> serviceIds();

	/** Another writer appended to the stream between load and append. */
	class ConcurrencyException extends RuntimeException {

		public ConcurrencyException(ServiceId id, long expected, long actual) {
			super("Stream " + id + " is at version " + actual + ", expected " + expected);
		}

	}

}
