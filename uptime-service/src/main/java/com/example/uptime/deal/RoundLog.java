package com.example.uptime.deal;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What this monitor saw in each round of one deal, and when it last reported each round. Health
 * samples are ANDed per round: a round is UP only if every sample in it was UP. Rounds the monitor
 * never saw (it wasn't running) have no entry and are never reported, so the program counts them as
 * down. Thread-safe.
 */
final class RoundLog {

	private final TreeMap<Integer, Boolean> witnessed = new TreeMap<>();

	private final Map<Integer, Instant> lastSent = new HashMap<>();

	/**
	 * Adds one health sample to a round.
	 *
	 * @param round the zero-based round the sample fell in
	 * @param up    whether the service was healthy
	 */
	synchronized void witness(int round, boolean up) {
		witnessed.merge(round, up, Boolean::logicalAnd);
	}

	/**
	 * @return a copy of every witnessed round and its UP/DOWN result so far, in round order
	 */
	synchronized Map<Integer, Boolean> snapshot() {
		return new TreeMap<>(witnessed);
	}

	/**
	 * Tells whether a round should be (re)sent now.
	 *
	 * @param round the round
	 * @param now   the current time
	 * @param retry how long to wait after a send before sending the same round again
	 * @return {@code true} if it was never sent, or was last sent at least {@code retry} ago
	 */
	synchronized boolean due(int round, Instant now, Duration retry) {
		Instant sent = lastSent.get(round);
		return sent == null || !now.isBefore(sent.plus(retry));
	}

	/**
	 * Remembers that a round was just sent (or tried).
	 *
	 * @param round the round
	 * @param now   the current time
	 */
	synchronized void markSent(int round, Instant now) {
		lastSent.put(round, now);
	}

	/**
	 * Drops a round the chain has recorded; it needs no more reports.
	 *
	 * @param round the round
	 */
	synchronized void forget(int round) {
		witnessed.remove(round);
		lastSent.remove(round);
	}

}
