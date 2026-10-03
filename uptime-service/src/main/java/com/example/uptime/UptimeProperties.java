package com.example.uptime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param sampleIntervalMs     how often the health state is sampled
 * @param flushIntervalMs      how often aggregated samples are written to the database
 * @param maxRangeSeconds      largest range the uptime list endpoint will return
 * @param defaultRangeSeconds  range returned when the caller gives no bounds
 */
@ConfigurationProperties("uptime")
public record UptimeProperties(long sampleIntervalMs, long flushIntervalMs, long maxRangeSeconds,
		long defaultRangeSeconds) {
}
