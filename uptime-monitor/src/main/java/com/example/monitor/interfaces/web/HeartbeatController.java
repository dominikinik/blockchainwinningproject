package com.example.monitor.interfaces.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.monitor.domain.HealthCheckResult.Outcome;
import com.example.monitor.domain.heartbeat.Heartbeat;
import com.example.monitor.domain.heartbeat.Heartbeat.Report;
import com.example.monitor.domain.heartbeat.HeartbeatLog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Heartbeats", description = "The oracle's per-round provider probes and the delivery of their observations")
public class HeartbeatController {

	static final int MAX_LIMIT = 500;

	private final HeartbeatLog heartbeats;

	public HeartbeatController(HeartbeatLog heartbeats) {
		this.heartbeats = heartbeats;
	}

	@GetMapping("/api/heartbeats")
	@Operation(summary = "The latest heartbeats of every deal, newest first",
			description = "limit is 1.." + MAX_LIMIT + " (default 50); anything else is a 400.")
	public List<HeartbeatResponse> recent(@RequestParam(defaultValue = "50") int limit) {
		return heartbeats.recent(checked(limit)).stream().map(HeartbeatResponse::of).toList();
	}

	@GetMapping("/api/deals/{address}/heartbeats")
	@Operation(summary = "The latest heartbeats of one deal, newest first; empty for an unknown deal",
			description = "limit is 1.." + MAX_LIMIT + " (default 50); anything else is a 400.")
	public List<HeartbeatResponse> forDeal(@PathVariable String address,
			@RequestParam(defaultValue = "50") int limit) {
		return heartbeats.forDeal(address, checked(limit)).stream().map(HeartbeatResponse::of).toList();
	}

	private static int checked(int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
		}
		return limit;
	}

	/**
	 * One heartbeat. {@code round} is the on-chain round index from 0; {@code up} is true only for {@code HEALTHY};
	 * {@code httpStatus} is null when the provider didn't answer; {@code signature} is the {@code record_observation}
	 * once sent.
	 */
	public record HeartbeatResponse(long id, String dealAddress, int round, Instant checkedAt, boolean up,
			Outcome outcome, Integer httpStatus, String detail, long latencyMs, Report report, String reportError,
			String signature) {

		static HeartbeatResponse of(Heartbeat h) {
			return new HeartbeatResponse(h.id(), h.dealAddress(), h.round(), h.checkedAt(), h.up(), h.outcome(),
					h.httpStatus(), h.detail(), h.latencyMs(), h.report(), h.reportError(), h.signature());
		}

	}

}
