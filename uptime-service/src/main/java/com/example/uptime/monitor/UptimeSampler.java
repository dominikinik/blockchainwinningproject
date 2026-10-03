package com.example.uptime.monitor;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.health.contributor.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.uptime.state.ApplicationStateHealthIndicator;
import com.example.uptime.uptime.UptimeRecord;
import com.example.uptime.uptime.UptimeRecordRepository;

/**
 * Samples the health state every {@code uptime.sample-interval-ms} (10 ms) into per-second buckets
 * in memory, and every {@code uptime.flush-interval-ms} (1 s) writes all completed seconds to the
 * database.
 */
@Component
public class UptimeSampler {

	private final ApplicationStateHealthIndicator healthIndicator;

	private final UptimeRecordRepository repository;

	private final Clock clock;

	/** Pending (not yet persisted) buckets keyed by epoch second. Guarded by {@code this}. */
	private final TreeMap<Long, Bucket> buckets = new TreeMap<>();

	public UptimeSampler(ApplicationStateHealthIndicator healthIndicator, UptimeRecordRepository repository,
			Clock clock) {
		this.healthIndicator = healthIndicator;
		this.repository = repository;
		this.clock = clock;
	}

	@Scheduled(fixedRateString = "${uptime.sample-interval-ms}")
	public void sample() {
		boolean up = Status.UP.equals(healthIndicator.health().getStatus());
		long second = clock.instant().getEpochSecond();
		synchronized (this) {
			buckets.computeIfAbsent(second, s -> new Bucket()).add(up);
		}
	}

	@Scheduled(fixedRateString = "${uptime.flush-interval-ms}")
	public void flush() {
		List<UptimeRecord> completed = drainCompleted(clock.instant().getEpochSecond());
		// TODO: handle rejected transactions when sending data to the database (connection lost,
		// timeout, constraint violation, DB down). Right now the drained buckets are lost if saveAll
		// fails, so those seconds later read as "down". Consider re-queueing them and retrying with
		// backoff, upserting so a retried second does not conflict with an existing row.
		if (!completed.isEmpty()) {
			repository.saveAll(completed);
		}
	}

	/** Removes and returns every bucket older than {@code currentSecond}; the current second is still filling. */
	private synchronized List<UptimeRecord> drainCompleted(long currentSecond) {
		List<UptimeRecord> records = new ArrayList<>();
		Iterator<Map.Entry<Long, Bucket>> it = buckets.headMap(currentSecond).entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Long, Bucket> e = it.next();
			Bucket b = e.getValue();
			Instant ts = Instant.ofEpochSecond(e.getKey()).truncatedTo(ChronoUnit.SECONDS);
			records.add(new UptimeRecord(ts, b.upSamples == b.samples, b.samples, b.upSamples));
			it.remove();
		}
		return records;
	}

	private static final class Bucket {

		int samples;

		int upSamples;

		void add(boolean up) {
			samples++;
			if (up) {
				upSamples++;
			}
		}

	}

}
