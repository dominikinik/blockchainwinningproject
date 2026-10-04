package com.example.monitor.application;

import com.example.monitor.domain.TrackingEvent;

/**
 * Reacts to a stored event that {@linkplain TrackingEvent#triggersDowntimeReport() triggers a report}
 * ({@code Downtime}, {@code InternalErrorHappened}, {@code TrackingFinished}). Called after the event is stored;
 * an exception is logged and never undoes the event.
 */
public interface TrackingEventListener {

	void onTrackingEvent(TrackingEvent event);

}
