package com.example.monitor.application;

import com.example.monitor.domain.TrackingEvent;

/**
 * Reacts to stored health-check observations and lifecycle events. Called after the event is stored;
 * an exception is logged and never undoes the event.
 */
public interface TrackingEventListener {

	void onTrackingEvent(TrackingEvent event);

}
