package com.example.monitor.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

import com.example.monitor.domain.heartbeat.Heartbeat;
import com.example.monitor.domain.heartbeat.HeartbeatLog;

/** {@link HeartbeatLog} in a list, for application tests. */
public class InMemoryHeartbeatLog implements HeartbeatLog {

	private static final Comparator<Heartbeat> NEWEST_FIRST = Comparator.comparing(Heartbeat::checkedAt)
		.thenComparing(Heartbeat::id)
		.reversed();

	private final List<Heartbeat> rows = new ArrayList<>();

	@Override
	public synchronized Heartbeat append(Heartbeat heartbeat) {
		Heartbeat stored = heartbeat.withId(rows.size() + 1L);
		rows.add(stored);
		return stored;
	}

	@Override
	public synchronized void update(Heartbeat heartbeat) {
		int index = heartbeat.id() == null ? -1 : (int) (heartbeat.id() - 1);
		if (index < 0 || index >= rows.size()) {
			throw new NoSuchElementException("Heartbeat " + heartbeat.id() + " is not logged");
		}
		Heartbeat old = rows.get(index);
		rows.set(index, new Heartbeat(old.id(), old.dealAddress(), old.round(), old.checkedAt(), old.outcome(),
				old.httpStatus(), old.detail(), old.latencyMs(), heartbeat.report(), heartbeat.reportError(),
				heartbeat.signature()));
	}

	@Override
	public synchronized List<Heartbeat> recent(int limit) {
		return rows.stream().sorted(NEWEST_FIRST).limit(limit).toList();
	}

	@Override
	public synchronized List<Heartbeat> forDeal(String dealAddress, int limit) {
		return rows.stream().filter(h -> h.dealAddress().equals(dealAddress)).sorted(NEWEST_FIRST).limit(limit).toList();
	}

}
