package com.example.monitor.application;

import java.util.NoSuchElementException;

import com.example.monitor.domain.ServiceId;

/** No events were ever recorded for the service. */
public class TrackingNotFoundException extends NoSuchElementException {

	public TrackingNotFoundException(ServiceId id) {
		super("Service " + id + " was never tracked");
	}

}
