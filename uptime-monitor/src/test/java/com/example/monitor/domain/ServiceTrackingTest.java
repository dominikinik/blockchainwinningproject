package com.example.monitor.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;

class ServiceTrackingTest {

	static final ServiceId ID = ServiceId.of("11111111-1111-1111-1111-111111111111");

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

	static final Duration INTERVAL = Duration.ofSeconds(2);

	static final String URL = "http://localhost:8080/api/health";

	static final HealthCheckResult DOWN = HealthCheckResult.fromResponse(200, "DOWN");

	static final HealthCheckResult NOT_FOUND = HealthCheckResult.fromResponse(404, null);

	static final HealthCheckResult UP = HealthCheckResult.fromResponse(200, "UP");

	static final HealthCheckResult ERROR = HealthCheckResult.fromResponse(500, null);

	@Test
	void startRecordsTrackingStartedAsTheFirstEvent() {
		ServiceTracking tracking = ServiceTracking.newTracking(ID);
		assertThat(tracking.exists()).isFalse();

		tracking.start(URL, INTERVAL, T0);

		assertThat(tracking.isActive()).isTrue();
		assertThat(tracking.exists()).isTrue();
		assertThat(tracking.pullPendingEvents()).containsExactly(new TrackingStarted(ID, URL, INTERVAL, T0));
		assertThat(tracking.version()).isEqualTo(1);
		assertThat(tracking.pullPendingEvents()).isEmpty();
	}

	@Test
	void recordsHealthyAndFailedCheckObservations() {
		ServiceTracking tracking = started();

		TrackingEvent healthy = tracking.recordCheck(UP, T0.plusSeconds(2));
		TrackingEvent down = tracking.recordCheck(DOWN, T0.plusSeconds(4));
		TrackingEvent notFound = tracking.recordCheck(NOT_FOUND, T0.plusSeconds(6));
		TrackingEvent error = tracking.recordCheck(ERROR, T0.plusSeconds(8));
		TrackingEvent unreachable = tracking.recordCheck(HealthCheckResult.unreachable("refused"),
				T0.plusSeconds(10));

		assertThat(down).isEqualTo(new Downtime(ID, 200, "HTTP 200, status DOWN", T0.plusSeconds(4)));
		assertThat(notFound).isEqualTo(new Downtime(ID, 404, "HTTP 404", T0.plusSeconds(6)));
		assertThat(error).isEqualTo(new InternalErrorHappened(ID, 500, "HTTP 500", T0.plusSeconds(8)));
		assertThat(unreachable).isEqualTo(new InternalErrorHappened(ID, null, "refused", T0.plusSeconds(10)));
		assertThat(healthy).isEqualTo(new TrackingEvent.HealthCheckSucceeded(ID, T0.plusSeconds(2)));
		assertThat(tracking.pullPendingEvents()).containsExactly(healthy, down, notFound, error, unreachable);
	}

	@Test
	void totalDowntimeIsOneIntervalPerDowntimeEventAndIgnoresInternalErrors() {
		ServiceTracking tracking = started();
		tracking.recordCheck(DOWN, T0.plusSeconds(2));
		tracking.recordCheck(ERROR, T0.plusSeconds(4));
		tracking.recordCheck(NOT_FOUND, T0.plusSeconds(6));
		tracking.recordCheck(UP, T0.plusSeconds(8));

		TrackingSummary summary = tracking.summary();
		assertThat(summary.totalDowntime()).isEqualTo(Duration.ofSeconds(4));
		assertThat(summary.downtimeChecks()).isEqualTo(2);
		assertThat(summary.internalErrors()).isEqualTo(1);
		assertThat(summary.eventCount()).isEqualTo(5);
	}

	@Test
	void finishRecordsTrackingFinishedAndStopsFurtherChecks() {
		ServiceTracking tracking = started();
		tracking.finish(T0.plusSeconds(10));

		assertThat(tracking.isActive()).isFalse();
		assertThat(tracking.summary().finishedAt()).isEqualTo(T0.plusSeconds(10));
		assertThat(tracking.pullPendingEvents()).containsExactly(new TrackingFinished(ID, T0.plusSeconds(10)));
		assertThatThrownBy(() -> tracking.recordCheck(DOWN, T0.plusSeconds(12)))
			.isInstanceOf(TrackingException.NotActive.class);
		assertThatThrownBy(() -> tracking.finish(T0.plusSeconds(12))).isInstanceOf(TrackingException.NotActive.class);
	}

	@Test
	void commandsOnAnUntrackedServiceAreRejected() {
		ServiceTracking tracking = ServiceTracking.newTracking(ID);
		assertThatThrownBy(() -> tracking.recordCheck(UP, T0)).isInstanceOf(TrackingException.NotActive.class);
		assertThatThrownBy(() -> tracking.finish(T0)).isInstanceOf(TrackingException.NotActive.class);
	}

	@Test
	void startingAnActiveServiceIsRejected() {
		ServiceTracking tracking = started();
		assertThatThrownBy(() -> tracking.start(URL, INTERVAL, T0)).isInstanceOf(TrackingException.AlreadyActive.class)
			.hasMessageContaining(ID.toString());
	}

	@Test
	void nonPositiveIntervalIsRejected() {
		ServiceTracking tracking = ServiceTracking.newTracking(ID);
		assertThatIllegalArgumentException().isThrownBy(() -> tracking.start(URL, Duration.ZERO, T0));
		assertThatIllegalArgumentException().isThrownBy(() -> tracking.start(URL, Duration.ofSeconds(-1), T0));
	}

	@Test
	void restartingKeepsEarlierDowntimeAndUsesTheNewInterval() {
		ServiceTracking tracking = started();
		tracking.recordCheck(DOWN, T0.plusSeconds(2));
		tracking.finish(T0.plusSeconds(3));
		tracking.start(URL, Duration.ofSeconds(5), T0.plusSeconds(100));
		tracking.recordCheck(DOWN, T0.plusSeconds(105));

		TrackingSummary summary = tracking.summary();
		assertThat(summary.active()).isTrue();
		assertThat(summary.finishedAt()).isNull();
		assertThat(summary.startedAt()).isEqualTo(T0.plusSeconds(100));
		assertThat(summary.totalDowntime()).isEqualTo(Duration.ofSeconds(7));
	}

	@Test
	void rehydratingTheEventsGivesTheSameState() {
		ServiceTracking original = started();
		original.recordCheck(DOWN, T0.plusSeconds(2));
		original.recordCheck(ERROR, T0.plusSeconds(4));
		original.finish(T0.plusSeconds(6));
		List<TrackingEvent> history = new ArrayList<>(List.of(new TrackingStarted(ID, URL, INTERVAL, T0)));
		history.addAll(original.pullPendingEvents());

		ServiceTracking replayed = ServiceTracking.rehydrate(ID, history);

		assertThat(replayed.summary()).isEqualTo(original.summary());
		assertThat(replayed.version()).isEqualTo(4);
		assertThat(replayed.pullPendingEvents()).isEmpty();
	}

	@Test
	void reportReflectsTheAggregatedDowntimeAtTheTrigger() {
		ServiceTracking tracking = started();
		tracking.recordCheck(DOWN, T0.plusSeconds(2));
		tracking.recordCheck(ERROR, T0.plusSeconds(4));
		tracking.finish(T0.plusSeconds(6));
		TrackingEvent finished = tracking.pullPendingEvents().getLast();

		assertThat(tracking.report(finished)).isEqualTo(
				new DowntimeReport(ID, "TrackingFinished", Duration.ofSeconds(2), 1, 1, false, T0.plusSeconds(6)));
	}

	@Test
	void onlyTrackingStartedDoesNotTriggerAReport() {
		assertThat(new TrackingStarted(ID, URL, INTERVAL, T0).triggersDowntimeReport()).isFalse();
		assertThat(new Downtime(ID, 404, "", T0).triggersDowntimeReport()).isTrue();
		assertThat(new InternalErrorHappened(ID, null, "", T0).triggersDowntimeReport()).isTrue();
		assertThat(new TrackingFinished(ID, T0).triggersDowntimeReport()).isTrue();
		assertThat(new TrackingFinished(ID, T0).type()).isEqualTo("TrackingFinished");
	}

	@Test
	void rehydratingAnInvalidHistoryIsRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ServiceTracking.rehydrate(ID, List.of(new Downtime(ID, 404, "", T0))));
		ServiceId other = ServiceId.newId();
		assertThatIllegalArgumentException().isThrownBy(
				() -> ServiceTracking.rehydrate(ID, List.of(new TrackingStarted(other, URL, INTERVAL, T0))));
		assertThat(ServiceTracking.rehydrate(ID, List.of()).exists()).isFalse();
	}

	@Test
	void serviceIdIsAUuid() {
		assertThat(ID.toString()).isEqualTo("11111111-1111-1111-1111-111111111111");
		assertThat(ServiceId.newId()).isNotEqualTo(ServiceId.newId());
		assertThatIllegalArgumentException().isThrownBy(() -> ServiceId.of("not-a-uuid"));
		assertThatThrownBy(() -> new ServiceId(null)).isInstanceOf(NullPointerException.class);
	}

	private static ServiceTracking started() {
		ServiceTracking tracking = ServiceTracking.newTracking(ID);
		tracking.start(URL, INTERVAL, T0);
		tracking.pullPendingEvents();
		return tracking;
	}

}
