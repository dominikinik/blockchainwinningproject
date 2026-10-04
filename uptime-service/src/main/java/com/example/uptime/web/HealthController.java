package com.example.uptime.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.uptime.state.ApplicationStateService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The health endpoint that {@code uptime-monitor} subscribes to. Unlike {@code /actuator/health}, which
 * answers 503 when stopped, this always answers 200 and reports the state in the body, so a stopped
 * service reads as downtime and only real failures read as errors.
 */
@RestController
@RequestMapping("/api/health")
@Tag(name = "Health", description = "Health endpoint for uptime-monitor subscriptions")
public class HealthController {

	private final ApplicationStateService state;

	public HealthController(ApplicationStateService state) {
		this.state = state;
	}

	@GetMapping
	@Operation(summary = "Logical health, always HTTP 200",
			description = "{\"status\":\"UP\"} while started, {\"status\":\"DOWN\"} after POST /api/application/stop.")
	public HealthResponse health() {
		return new HealthResponse(state.isUp() ? "UP" : "DOWN");
	}

	public record HealthResponse(String status) {
	}

}
