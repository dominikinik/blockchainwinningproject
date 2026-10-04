package com.example.monitor.domain.heartbeat;

import java.util.List;

/** Port: the durable log of deal heartbeats. */
public interface HeartbeatLog {

	/** Stores a new heartbeat. @return it with its assigned id */
	Heartbeat append(Heartbeat heartbeat);

	/** Replaces the report fields of a stored heartbeat. @throws java.util.NoSuchElementException if it doesn't exist */
	void update(Heartbeat heartbeat);

	/** The latest heartbeats of every deal, newest first. */
	List<Heartbeat> recent(int limit);

	/** The latest heartbeats of one deal, newest first; empty for an unknown deal. */
	List<Heartbeat> forDeal(String dealAddress, int limit);

}
