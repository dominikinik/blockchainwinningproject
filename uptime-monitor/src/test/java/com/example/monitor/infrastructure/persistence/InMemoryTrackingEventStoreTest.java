package com.example.monitor.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;
import com.example.monitor.domain.TrackingEventStore.ConcurrencyException;

class InMemoryTrackingEventStoreTest {

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

	InMemoryTrackingEventStore store = new InMemoryTrackingEventStore();

	ServiceId a = ServiceId.newId();

	ServiceId b = ServiceId.newId();

	@Test
	void appendsAndLoadsStreamsInOrder() {
		TrackingEvent started = new TrackingStarted(a, "http://a", Duration.ofSeconds(2), T0);
		TrackingEvent finished = new TrackingFinished(a, T0.plusSeconds(1));
		store.append(a, 0, List.of(started));
		store.append(b, 0, List.of(new TrackingStarted(b, "http://b", Duration.ofSeconds(2), T0)));
		store.append(a, 1, List.of(finished));

		assertThat(store.load(a)).containsExactly(started, finished);
		assertThat(store.serviceIds()).containsExactly(a, b);
	}

	@Test
	void unknownStreamIsEmpty() {
		assertThat(store.load(a)).isEmpty();
		assertThat(store.serviceIds()).isEmpty();
	}

	@Test
	void wrongExpectedVersionIsAConflict() {
		store.append(a, 0, List.of(new TrackingStarted(a, "http://a", Duration.ofSeconds(2), T0)));

		assertThatThrownBy(() -> store.append(a, 0, List.of(new TrackingFinished(a, T0))))
			.isInstanceOf(ConcurrencyException.class)
			.hasMessageContaining("version 1, expected 0");
		assertThatThrownBy(() -> store.append(b, 3, List.of(new TrackingFinished(b, T0))))
			.isInstanceOf(ConcurrencyException.class);
		assertThat(store.load(a)).hasSize(1);
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
	}

}
