package com.example.uptime.uptime;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.uptime.UptimeProperties;

/**
 * Reads recorded seconds; any second without a record is reported as down.
 * <p>
 * TODO: handle rejected transactions when receiving data from the database (connection refused,
 * timeout, read-only transaction rolled back). These currently surface as a 500 to the caller. We
 * need to decide whether to retry, return 503 with a clear error, or fall back to the not-yet-flushed
 * in-memory samples; we must not silently report the range as "down".
 */
@Service
@Transactional(readOnly = true)
public class UptimeQueryService {

	private final UptimeRecordRepository repository;

	private final UptimeProperties properties;

	private final Clock clock;

	public UptimeQueryService(UptimeRecordRepository repository, UptimeProperties properties, Clock clock) {
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	public UptimePoint at(Instant time) {
		Instant second = time.truncatedTo(ChronoUnit.SECONDS);
		return repository.findById(second)
			.map(r -> new UptimePoint(second, !r.isUp()))
			.orElseGet(() -> new UptimePoint(second, true));
	}

	/**
	 * One entry per second in {@code [from, to]} (both inclusive, truncated to seconds). Null bounds
	 * default to the last {@code uptime.default-range-seconds} seconds.
	 */
	public List<UptimePoint> range(Instant from, Instant to) {
		Instant end = (to != null ? to : clock.instant()).truncatedTo(ChronoUnit.SECONDS);
		Instant start = (from != null ? from : end.minusSeconds(properties.defaultRangeSeconds() - 1))
			.truncatedTo(ChronoUnit.SECONDS);
		if (start.isAfter(end)) {
			throw new IllegalArgumentException("'from' must not be after 'to'");
		}
		long seconds = ChronoUnit.SECONDS.between(start, end) + 1;
		if (seconds > properties.maxRangeSeconds()) {
			throw new IllegalArgumentException(
					"Range of " + seconds + "s exceeds maximum of " + properties.maxRangeSeconds() + "s");
		}

		Map<Instant, UptimeRecord> recorded = repository.findByTimestampBetweenOrderByTimestamp(start, end)
			.stream()
			.collect(Collectors.toMap(UptimeRecord::getTimestamp, Function.identity()));

		List<UptimePoint> points = new ArrayList<>((int) seconds);
		for (Instant t = start; !t.isAfter(end); t = t.plusSeconds(1)) {
			UptimeRecord r = recorded.get(t);
			points.add(new UptimePoint(t, r == null || !r.isUp()));
		}
		return points;
	}

}
