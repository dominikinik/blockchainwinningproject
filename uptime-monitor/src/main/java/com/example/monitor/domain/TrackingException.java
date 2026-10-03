package com.example.monitor.domain;

/** A command that the current state of a {@link ServiceTracking} doesn't allow. */
public class TrackingException extends RuntimeException {

	public TrackingException(String message) {
		super(message);
	}

	/** Subscribing to a service that is already being tracked. */
	public static class AlreadyActive extends TrackingException {

		public AlreadyActive(ServiceId id) {
			super("Service " + id + " is already being tracked");
		}

	}

	/** Recording a check for, or unsubscribing from, a service that isn't being tracked. */
	public static class NotActive extends TrackingException {

		public NotActive(ServiceId id) {
			super("Service " + id + " is not being tracked");
		}

	}

}
