package com.example.monitor.interfaces.web;

import java.time.Instant;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.monitor.application.UptimeHistory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/uptime")
@Tag(name = "Uptime", description = "Per-second uptime of the provider, from the health results relayed since startup")
public class UptimeController {

	private final UptimeHistory history;

	public UptimeController(UptimeHistory history) {
		this.history = history;
	}

	@GetMapping
	@Operation(summary = "List uptime per second",
			description = "One entry per second in [from, to]. A second is down before the first relayed result or when "
					+ "it falls in the check interval of a DOWN result. Without parameters: the last 5 minutes.")
	public List<UptimeHistory.Point> list(
			@Parameter(description = "Range start (ISO-8601)", example = "2026-10-04T12:00:00Z")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@Parameter(description = "Range end (ISO-8601), inclusive; defaults to now")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return history.range(from, to);
	}

}
