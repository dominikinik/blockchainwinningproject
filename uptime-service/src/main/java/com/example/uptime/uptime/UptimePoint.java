package com.example.uptime.uptime;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Uptime state for one second")
public record UptimePoint(
		@Schema(description = "Start of the second, UTC", example = "2026-10-03T12:00:00Z") Instant time,
		@Schema(description = "True if the application was down (or no data was recorded) during this second") boolean down) {
}
