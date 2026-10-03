package com.example.uptime.web;

import java.time.Instant;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.uptime.uptime.UptimePoint;
import com.example.uptime.uptime.UptimeQueryService;

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
			description = "Returns one entry per second in [from, to]. Seconds with no recorded data are reported as down. "
					+ "Without parameters, returns the last 5 minutes.")
	public List<UptimePoint> list(
			@Parameter(description = "Range start (ISO-8601), e.g. 2026-10-03T12:00:00Z", example = "2026-10-03T12:00:00Z")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@Parameter(description = "Range end (ISO-8601), inclusive; defaults to now")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return queryService.range(from, to);
	}

	@GetMapping("/at")
	@Operation(summary = "Uptime at a given time",
			description = "Returns whether the application was down during the second containing the given time. "
					+ "Missing data is reported as down.")
	public UptimePoint at(
			@Parameter(description = "Point in time (ISO-8601)", example = "2026-10-03T12:00:00Z", required = true)
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant time) {
		return queryService.at(time);
	}

}
