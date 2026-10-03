package com.example.monitor.interfaces.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.monitor.application.TrackingService;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;
import com.example.monitor.domain.TrackingSummary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/subscriptions")
@Tag(name = "Subscriptions", description = "Track a service's health endpoint and publish its downtime on chain")
public class SubscriptionController {

	private final TrackingService tracking;

	public SubscriptionController(TrackingService tracking) {
		this.tracking = tracking;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Start tracking a service",
			description = "Records TrackingStarted, then calls healthUrl every check interval. Pass serviceId to "
					+ "track under a known UUID (e.g. to resume a finished one); omit it to get a new one.")
	public SubscriptionResponse subscribe(@RequestBody SubscribeRequest request) {
		ServiceId requested = request.serviceId() == null ? null : new ServiceId(request.serviceId());
		return SubscriptionResponse.of(tracking.get(tracking.subscribe(request.healthUrl(), requested)));
	}

	@DeleteMapping("/{serviceId}")
	@Operation(summary = "Stop tracking a service",
			description = "Records TrackingFinished and publishes the service's total downtime.")
	public SubscriptionResponse unsubscribe(@PathVariable UUID serviceId) {
		ServiceId id = new ServiceId(serviceId);
		tracking.unsubscribe(id);
		return SubscriptionResponse.of(tracking.get(id));
	}

	@GetMapping
	@Operation(summary = "Every service that was ever tracked")
	public List<SubscriptionResponse> list() {
		return tracking.list().stream().map(SubscriptionResponse::of).toList();
	}

	@GetMapping("/{serviceId}")
	@Operation(summary = "A service's tracking state and total downtime")
	public SubscriptionResponse get(@PathVariable UUID serviceId) {
		return SubscriptionResponse.of(tracking.get(new ServiceId(serviceId)));
	}

	@GetMapping("/{serviceId}/events")
	@Operation(summary = "A service's events, oldest first")
	public List<EventResponse> events(@PathVariable UUID serviceId) {
		return tracking.events(new ServiceId(serviceId)).stream().map(EventResponse::of).toList();
	}

	/**
	 * @param healthUrl absolute http(s) URL of the service's health endpoint
	 * @param serviceId optional UUID to track the service under
	 */
	public record SubscribeRequest(String healthUrl, UUID serviceId) {
	}

	public record SubscriptionResponse(UUID serviceId, String healthUrl, boolean active, long checkIntervalMs,
			Instant startedAt, Instant finishedAt, long totalDowntimeMs, long downtimeChecks, long internalErrors,
			long eventCount) {

		static SubscriptionResponse of(TrackingSummary s) {
			return new SubscriptionResponse(s.serviceId().value(), s.healthUrl(), s.active(),
					s.checkInterval().toMillis(), s.startedAt(), s.finishedAt(), s.totalDowntime().toMillis(),
					s.downtimeChecks(), s.internalErrors(), s.eventCount());
		}

	}

	/**
	 * @param httpStatus the health endpoint's status for Downtime / InternalErrorHappened, else {@code null}
	 * @param detail     the health URL for TrackingStarted, what the check saw for failures, else {@code null}
	 */
	public record EventResponse(String type, UUID serviceId, Instant occurredAt, Integer httpStatus, String detail) {

		static EventResponse of(TrackingEvent event) {
			UUID id = event.serviceId().value();
			return switch (event) {
				case TrackingStarted e -> new EventResponse(e.type(), id, e.occurredAt(), null, e.healthUrl());
				case Downtime e -> new EventResponse(e.type(), id, e.occurredAt(), e.httpStatus(), e.reason());
				case InternalErrorHappened e ->
					new EventResponse(e.type(), id, e.occurredAt(), e.httpStatus(), e.reason());
				case TrackingFinished e -> new EventResponse(e.type(), id, e.occurredAt(), null, null);
			};
		}

	}

}
