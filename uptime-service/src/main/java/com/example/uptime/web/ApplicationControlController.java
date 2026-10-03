package com.example.uptime.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.uptime.state.ApplicationStateService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/application")
@Tag(name = "Application control", description = "Switch the reported health without stopping the process")
public class ApplicationControlController {

	private final ApplicationStateService state;

	public ApplicationControlController(ApplicationStateService state) {
		this.state = state;
	}

	@PostMapping("/stop")
	@Operation(summary = "Mark the application as down",
			description = "Marks the local application health DOWN without stopping tracking or the process. "
								+ "An active local-probe tracking session records downtime bad events.")
	public StateResponse stop() {
		state.stop();
		return current();
	}

	@PostMapping("/start")
	@Operation(summary = "Mark the application as up",
			description = "Marks the local application health UP without starting or stopping tracking. "
								+ "Composite Actuator health may still be DOWN because of other contributors.")
	public StateResponse start() {
		state.start();
		return current();
	}

	@GetMapping("/state")
	@Operation(summary = "Current logical state")
	public StateResponse current() {
		return new StateResponse(state.isUp() ? "UP" : "DOWN");
	}

	public record StateResponse(String status) {
	}

}
