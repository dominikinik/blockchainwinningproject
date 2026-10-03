package com.example.uptime.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.example.uptime.tracking.application.TrackingService;
import com.example.uptime.tracking.domain.TrackingSession;
import com.example.uptime.tracking.domain.TrackingStatus;
import com.example.uptime.tracking.domain.TrackingEvent;
import com.example.uptime.tracking.domain.TrackingEventType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tracking")
public class TrackingController {
	private final TrackingService service;

	public TrackingController(TrackingService service) { this.service = service; }

	@PostMapping("/start")
	@ResponseStatus(HttpStatus.CREATED)
	public State start() { return State.of(service.start()); }

	@PostMapping("/stop")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public State stop() { return State.of(service.stop()); }

	@GetMapping("/state")
	public State state() {
		return service.state().map(State::of)
				.orElseGet(() -> new State(null, null, null, TrackingStatus.STOPPED, null));
	}

	@GetMapping("/events")
	public List<Event> events(@RequestParam UUID sessionId) {
		return service.events(sessionId).stream().map(Event::of).toList();
	}

	public record State(UUID id,
			@JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt,
			@JsonFormat(shape = JsonFormat.Shape.STRING) Instant stoppedAt, TrackingStatus status,
			@JsonFormat(shape = JsonFormat.Shape.STRING) Instant committedThrough) {
		static State of(TrackingSession s) {
			return new State(s.id(), s.startedAt(), s.stoppedAt(), s.status(), s.committedThrough());
		}
	}

	public record Event(UUID id, UUID sessionId, TrackingEventType type,
			@JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt, String reason) {
		static Event of(TrackingEvent e) {
			return new Event(e.id(), e.sessionId(), e.type(), e.occurredAt(), e.reason());
		}
	}
}
