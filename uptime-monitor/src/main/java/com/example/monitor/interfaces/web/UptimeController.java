package com.example.monitor.interfaces.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.monitor.application.UptimeHistory;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.infrastructure.config.MonitorProperties;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/uptime")
@Tag(name = "Uptime", description = "Per-second uptime of a tracked service, derived from its events")
public class UptimeController {

	private final UptimeHistory history;

	private final MonitorProperties properties;

	public UptimeController(UptimeHistory history, MonitorProperties properties) {
		this.history = history;
		this.properties = properties;
	}

	@GetMapping
	@Operation(summary = "List uptime per second",
			description = "One entry per second in [from, to]. A second is down when the service wasn't tracked or "
					+ "falls in the check interval of a Downtime event. Without parameters: the last 5 minutes of the "
					+ "default service.")
	public List<UptimeHistory.Point> list(
			@Parameter(description = "Range start (ISO-8601)", example = "2026-10-04T12:00:00Z")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@Parameter(description = "Range end (ISO-8601), inclusive; defaults to now")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@Parameter(description = "Tracked service; defaults to the default service")
			@RequestParam(required = false) UUID serviceId) {
		MonitorProperties.DefaultService fallback = properties.defaultService();
		UUID id = serviceId != null ? serviceId : fallback.enabled() ? fallback.id() : null;
		if (id == null) {
			throw new IllegalArgumentException("serviceId is required: no default service is configured");
		}
		return history.range(new ServiceId(id), from, to);
	}

}
