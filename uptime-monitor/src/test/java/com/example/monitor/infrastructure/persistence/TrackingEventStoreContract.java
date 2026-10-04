package com.example.monitor.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;
import com.example.monitor.domain.TrackingEventStore;
import com.example.monitor.domain.TrackingEventStore.ConcurrencyException;

/** Behaviour every {@link TrackingEventStore} must have; run against each implementation. */
abstract class TrackingEventStoreContract {

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00.123456Z");

	TrackingEventStore store;

	ServiceId a = ServiceId.newId();

	ServiceId b = ServiceId.newId();

	abstract TrackingEventStore newStore();

	@BeforeEach
	void createStore() {
		store = newStore();
	}

	@Test
	void everyEventTypeRoundTrips() {
		List<TrackingEvent> events = List.of(new TrackingStarted(a, "http://a/health", Duration.ofSeconds(2), T0),
				new Downtime(a, 200, "HTTP 200, status DOWN", T0.plusSeconds(2)),
				new Downtime(a, 404, "HTTP 404", T0.plusSeconds(4)),
				new InternalErrorHappened(a, 500, "HTTP 500", T0.plusSeconds(6)),
				new InternalErrorHappened(a, null, "Connection refused", T0.plusSeconds(8)),
				new TrackingFinished(a, T0.plusSeconds(10)));

		store.append(a, 0, events);

		assertThat(store.load(a)).containsExactlyElementsOf(events);
	}

	@Test
	void appendsAndLoadsStreamsInOrder() {
		TrackingEvent started = new TrackingStarted(a, "http://a", Duration.ofSeconds(2), T0);
		TrackingEvent finished = new TrackingFinished(a, T0.plusSeconds(1));
		store.append(b, 0, List.of(new TrackingStarted(b, "http://b", Duration.ofSeconds(2), T0)));
		store.append(a, 0, List.of(started));
		store.append(a, 1, List.of(finished));

		assertThat(store.load(a)).containsExactly(started, finished);
		assertThat(store.serviceIds()).containsExactly(b, a);
	}

	@Test
	void unknownStreamIsEmpty() {
		assertThat(store.load(a)).isEmpty();
		assertThat(store.serviceIds()).isEmpty();
	}

	@Test
	void wrongExpectedVersionIsAConflictAndAppendsNothing() {
		store.append(a, 0, List.of(new TrackingStarted(a, "http://a", Duration.ofSeconds(2), T0)));

		assertThatThrownBy(() -> store.append(a, 0, List.of(new TrackingFinished(a, T0))))
			.isInstanceOf(ConcurrencyException.class)
			.hasMessageContaining("version 1, expected 0");
		assertThatThrownBy(() -> store.append(a, 2,
				List.of(new Downtime(a, 404, "", T0), new TrackingFinished(a, T0))))
			.isInstanceOf(ConcurrencyException.class);
		assertThatThrownBy(() -> store.append(b, 3, List.of(new TrackingFinished(b, T0))))
			.isInstanceOf(ConcurrencyException.class);
		assertThat(store.load(a)).hasSize(1);
		assertThat(store.load(b)).isEmpty();
	}

	@Test
	void eventsOfAnotherServiceAreRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> store.append(a, 0, List.of(new TrackingFinished(b, T0))));
		assertThat(store.serviceIds()).isEmpty();
	}

	@Test
	void appendingNothingCreatesNoStream() {
		store.append(a, 0, List.of());
		assertThat(store.serviceIds()).isEmpty();
	}

	@Test
	void loadedListsAreSnapshots() {
		store.append(a, 0, List.of(new TrackingStarted(a, "http://a", Duration.ofSeconds(2), T0)));
		List<TrackingEvent> loaded = store.load(a);
		store.append(a, 1, List.of(new TrackingFinished(a, T0)));
		assertThat(loaded).hasSize(1);
		assertThatThrownBy(() -> loaded.add(new TrackingFinished(a, T0)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

}
