package com.example.uptime.aggregation.application;

import java.util.List;

import com.example.uptime.aggregation.domain.UptimeEvent;

/** Stores finalized windows atomically; equal retries are safe, conflicting retries fail. */
public interface UptimeEventStore {

	void saveAll(List<UptimeEvent> events);

}
