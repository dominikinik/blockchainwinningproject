package com.example.monitor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.monitor.domain.DowntimeReport;
import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthProbe;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;
import com.example.monitor.domain.TrackingEventStore;
import com.example.monitor.domain.TrackingException;
import com.example.monitor.support.InMemoryTrackingEventStore;
import com.example.monitor.support.MutableClock;

class TrackingServiceTest {

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

	static final String URL = "http://provider:8080/api/health";

	MutableClock clock = new MutableClock(T0);

	InMemoryTrackingEventStore store = new InMemoryTrackingEventStore();

	FakeProbe probe = new FakeProbe();

	List<DowntimeReport> published = new CopyOnWriteArrayList<>();

	TrackingService service;

	@BeforeEach
	void setUp() {
		service = new TrackingService(store, probe, published::add, clock, Duration.ofSeconds(2));
	}

	@Test
	void subscribeRecordsTrackingStartedForANewUuidAndPublishesNothing() {
		ServiceId id = service.subscribe(" " + URL + " ", null);

		assertThat(service.events(id)).containsExactly(new TrackingStarted(id, URL, Duration.ofSeconds(2), T0));
		assertThat(service.get(id).active()).isTrue();
		assertThat(published).isEmpty();
	}

	@Test
	void subscribeUsesTheRequestedUuid() {
		ServiceId requested = ServiceId.newId();
		assertThat(service.subscribe(URL, requested)).isEqualTo(requested);
		assertThat(service.list()).extracting(s -> s.serviceId()).containsExactly(requested);
	}

	@Test
	void subscribingTwiceIsRejected() {
		ServiceId id = service.subscribe(URL, null);
		assertThatThrownBy(() -> service.subscribe(URL, id)).isInstanceOf(TrackingException.AlreadyActive.class);
		assertThat(service.events(id)).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "not a url", "ftp://host/health", "/api/health", "http://" })
	void invalidHealthUrlIsRejected(String url) {
		assertThatIllegalArgumentException().isThrownBy(() -> service.subscribe(url, null));
		assertThat(store.serviceIds()).isEmpty();
	}

	@Test
	void nullHealthUrlIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> service.subscribe(null, null));
	}

	@Test
	void healthyCheckRecordsAndPublishesNothing() {
		ServiceId id = service.subscribe(URL, null);
		probe.next(HealthCheckResult.fromResponse(200, "UP"));

		service.check(id);

		assertThat(service.events(id)).hasSize(1);
		assertThat(published).isEmpty();
		assertThat(probe.calls).containsExactly(URL);
	}

	@Test
	void downtimeIsRecordedAndTheAggregatedTotalPublished() {
		ServiceId id = service.subscribe(URL, null);

		probe.next(HealthCheckResult.fromResponse(200, "DOWN"));
		clock.set(T0.plusSeconds(2));
		service.check(id);
		probe.next(HealthCheckResult.fromResponse(404, null));
		clock.set(T0.plusSeconds(4));
		service.check(id);

		assertThat(service.events(id)).extracting(TrackingEvent::type)
			.containsExactly("TrackingStarted", "Downtime", "Downtime");
		assertThat(published).containsExactly(
				new DowntimeReport(id, "Downtime", Duration.ofSeconds(2), 1, 0, true, T0.plusSeconds(2)),
				new DowntimeReport(id, "Downtime", Duration.ofSeconds(4), 2, 0, true, T0.plusSeconds(4)));
	}

	@Test
	void internalErrorIsRecordedAndPublishedWithoutAddingDowntime() {
		ServiceId id = service.subscribe(URL, null);
		probe.next(HealthCheckResult.unreachable("Connection refused"));
		clock.set(T0.plusSeconds(2));

		service.check(id);

		assertThat(service.events(id).getLast())
			.isEqualTo(new InternalErrorHappened(id, null, "Connection refused", T0.plusSeconds(2)));
		assertThat(published).containsExactly(
				new DowntimeReport(id, "InternalErrorHappened", Duration.ZERO, 0, 1, true, T0.plusSeconds(2)));
	}

	@Test
	void unsubscribeRecordsTrackingFinishedAndPublishesTheFinalTotal() {
		ServiceId id = service.subscribe(URL, null);
		probe.next(HealthCheckResult.fromResponse(404, null));
		service.check(id);
		clock.set(T0.plusSeconds(10));

		service.unsubscribe(id);

		assertThat(service.events(id).getLast()).isEqualTo(new TrackingFinished(id, T0.plusSeconds(10)));
		assertThat(published.getLast()).isEqualTo(
				new DowntimeReport(id, "TrackingFinished", Duration.ofSeconds(2), 1, 0, false, T0.plusSeconds(10)));
		assertThat(service.get(id).active()).isFalse();
	}

	@Test
	void unsubscribeErrors() {
		ServiceId unknown = ServiceId.newId();
		assertThatThrownBy(() -> service.unsubscribe(unknown)).isInstanceOf(TrackingNotFoundException.class);

		ServiceId id = service.subscribe(URL, null);
		service.unsubscribe(id);
		assertThatThrownBy(() -> service.unsubscribe(id)).isInstanceOf(TrackingException.NotActive.class);
		assertThat(published).hasSize(1);
	}

	@Test
	void finishedServiceIsNotCheckedAndCanBeResubscribed() {
		ServiceId id = service.subscribe(URL, null);
		service.unsubscribe(id);

		service.check(id);
		service.checkActive();
		assertThat(probe.calls).isEmpty();

		service.subscribe(URL, id);
		assertThat(service.events(id)).extracting(TrackingEvent::type)
			.containsExactly("TrackingStarted", "TrackingFinished", "TrackingStarted");
	}

	@Test
	void checkOfAnUnknownServiceDoesNothing() {
		service.check(ServiceId.newId());
		assertThat(probe.calls).isEmpty();
		assertThat(store.serviceIds()).isEmpty();
	}

	@Test
	void checkThatRacesAnUnsubscribeIsDropped() {
		ServiceId id = service.subscribe(URL, null);
		probe.onCall = () -> service.unsubscribe(id);
		probe.next(HealthCheckResult.fromResponse(404, null));

		service.check(id);

		assertThat(service.events(id)).extracting(TrackingEvent::type)
			.containsExactly("TrackingStarted", "TrackingFinished");
	}

	@Test
	void checkActiveChecksEveryTrackedServiceOnly() {
		ServiceId a = service.subscribe("http://a/health", null);
		ServiceId b = service.subscribe("http://b/health", null);
		ServiceId c = service.subscribe("http://c/health", null);
		service.unsubscribe(c);
		probe.byUrl.put("http://a/health", HealthCheckResult.fromResponse(404, null));
		probe.byUrl.put("http://b/health", HealthCheckResult.fromResponse(200, "UP"));

		service.checkActive();

		assertThat(probe.calls).containsExactlyInAnyOrder("http://a/health", "http://b/health");
		assertThat(service.events(a).getLast()).isInstanceOf(Downtime.class);
		assertThat(service.events(b)).hasSize(1);
	}

	@Test
	void checkActiveKeepsGoingWhenOneCheckThrows() {
		ServiceId a = service.subscribe("http://a/health", null);
		ServiceId b = service.subscribe("http://b/health", null);
		probe.byUrl.put("http://b/health", HealthCheckResult.fromResponse(404, null));

		service.checkActive();

		assertThat(service.events(a)).hasSize(1);
		assertThat(service.events(b).getLast()).isInstanceOf(Downtime.class);
	}

	@Test
	void failedPublishKeepsTheEvent() {
		service = new TrackingService(store, probe, report -> {
			throw new IllegalStateException("RPC down");
		}, clock, Duration.ofSeconds(2));
		ServiceId id = service.subscribe(URL, null);
		probe.next(HealthCheckResult.fromResponse(404, null));

		service.check(id);
		service.unsubscribe(id);

		assertThat(service.events(id)).extracting(TrackingEvent::type)
			.containsExactly("TrackingStarted", "Downtime", "TrackingFinished");
	}

	@Test
	void concurrentAppendIsRetried() {
		AtomicInteger conflicts = new AtomicInteger(1);
		TrackingEventStore flaky = new TrackingEventStore() {
			@Override
			public void append(ServiceId id, long expectedVersion, List<TrackingEvent> events) {
				if (conflicts.getAndDecrement() > 0) {
					throw new ConcurrencyException(id, expectedVersion, expectedVersion + 1);
				}
				store.append(id, expectedVersion, events);
			}

			@Override
			public List<TrackingEvent> load(ServiceId id) {
				return store.load(id);
			}

			@Override
			public List<ServiceId> serviceIds() {
				return store.serviceIds();
			}
		};
		service = new TrackingService(flaky, probe, published::add, clock, Duration.ofSeconds(2));

		ServiceId id = service.subscribe(URL, null);

		assertThat(service.events(id)).hasSize(1);
	}

	@Test
	void persistentConflictGivesUp() {
		TrackingEventStore conflicting = new TrackingEventStore() {
			@Override
			public void append(ServiceId id, long expectedVersion, List<TrackingEvent> events) {
				throw new ConcurrencyException(id, expectedVersion, expectedVersion + 1);
			}

			@Override
			public List<TrackingEvent> load(ServiceId id) {
				return List.of();
			}

			@Override
			public List<ServiceId> serviceIds() {
				return List.of();
			}
		};
		service = new TrackingService(conflicting, probe, published::add, clock, Duration.ofSeconds(2));

		assertThatThrownBy(() -> service.subscribe(URL, null))
			.isInstanceOf(TrackingEventStore.ConcurrencyException.class);
	}

	@Test
	void readsOfAnUnknownServiceAreNotFound() {
		ServiceId unknown = ServiceId.newId();
		assertThatThrownBy(() -> service.get(unknown)).isInstanceOf(TrackingNotFoundException.class)
			.hasMessageContaining(unknown.toString());
		assertThatThrownBy(() -> service.events(unknown)).isInstanceOf(TrackingNotFoundException.class);
		assertThat(service.list()).isEmpty();
	}

	/** Returns queued results, or a per-URL result; a {@code null} result makes the call throw. */
	static class FakeProbe implements HealthProbe {

		final List<String> calls = new CopyOnWriteArrayList<>();

		final Map<String, HealthCheckResult> byUrl = new ConcurrentHashMap<>();

		final List<HealthCheckResult> queue = new ArrayList<>();

		Runnable onCall = () -> {
		};

		void next(HealthCheckResult result) {
			queue.add(result);
		}

		@Override
		public HealthCheckResult check(String healthUrl) {
			calls.add(healthUrl);
			onCall.run();
			HealthCheckResult result;
			synchronized (queue) {
				result = queue.isEmpty() ? byUrl.get(healthUrl) : queue.removeFirst();
			}
			if (result == null) {
				throw new IllegalStateException("No result for " + healthUrl);
			}
			return result;
		}

	}

}
