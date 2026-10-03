package com.example.uptime.web;

import java.time.Instant;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.uptime.aggregation.application.UptimeEventDetails;
import com.example.uptime.aggregation.application.UptimePoint;
import com.example.uptime.aggregation.application.UptimeQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/uptime")
@Tag(name = "Uptime", description = "Recorded uptime history of this service")
public class UptimeController {

	private final UptimeQueryService queryService;

	public UptimeController(UptimeQueryService queryService) {
		this.queryService = queryService;
	}

	@GetMapping
	@Operation(summary = "List uptime per second",
			description = "Returns session-specific seconds overlapping inclusive original bounds [from, to]. "
					+ "UNKNOWN/PENDING have null down; gaps in tracking return 422. "
					+ "Without bounds, uses the latest session's committed history.")
	public List<UptimePoint> list(
			@Parameter(description = "Range start (ISO-8601), e.g. 2026-10-03T12:00:00Z", example = "2026-10-03T12:00:00Z")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@Parameter(description = "Range end (ISO-8601), inclusive; defaults to latest committed coverage")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return queryService.range(from, to);
	}

	@GetMapping("/event")
	@Operation(summary = "Detailed uptime event at a given time",
			description = "Returns the exact containing session event, counts, bad events and unknown intervals. "
					+ "Unpersisted tracked windows are PENDING; unavailable measurements are UNKNOWN.")
	public UptimeEventDetails event(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant time) {
		return queryService.event(time);
	}

	@GetMapping("/at")
	@Operation(summary = "Uptime at a given time",
			description = "Selects the event containing the original timestamp, not another parent in the same second. "
					+ "Only FAILED is down; UNKNOWN/PENDING have null down.")
	public UptimePoint at(
			@Parameter(description = "Point in time (ISO-8601)", example = "2026-10-03T12:00:00Z", required = true)
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant time) {
		return queryService.at(time);
	}

}
