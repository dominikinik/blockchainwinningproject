package com.example.uptime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param sampleIntervalMs     how often the health state is sampled
 * @param flushIntervalMs      delay between completed-window persistence attempts
 * @param maxRangeSeconds      largest range the uptime list endpoint will return
 * @param defaultRangeSeconds  range returned when the caller gives no bounds
 * @param maxBufferedWindows   maximum number of open and unacknowledged windows in memory
 * @param persistenceBatchSize maximum number of completed events per transaction
 * @param retryInitialMs       initial persistence retry backoff
 * @param retryMaxMs           maximum persistence retry backoff
 * @param maxObservationGapMs  maximum inferred coverage following the latest observation
 */
@ConfigurationProperties("uptime")
public record UptimeProperties(long sampleIntervalMs, long flushIntervalMs, long maxRangeSeconds,
		long defaultRangeSeconds, @DefaultValue("3600") int maxBufferedWindows,
		@DefaultValue("60") int persistenceBatchSize, @DefaultValue("1000") long retryInitialMs,
		@DefaultValue("30000") long retryMaxMs, @DefaultValue("50") long maxObservationGapMs) {

	@ConstructorBinding
	public UptimeProperties {
		if (sampleIntervalMs <= 0 || flushIntervalMs <= 0 || maxRangeSeconds <= 0
				|| maxRangeSeconds > Integer.MAX_VALUE || defaultRangeSeconds <= 0
				|| defaultRangeSeconds > maxRangeSeconds || maxBufferedWindows <= 0
				|| persistenceBatchSize <= 0 || retryInitialMs <= 0 || retryMaxMs < retryInitialMs
								|| maxObservationGapMs <= 0) {
			throw new IllegalArgumentException("Uptime intervals, ranges, buffer limits and retry delays must be positive; "
					+ "default range must not exceed maximum range, and retry maximum must not be below initial delay");
		}
	}

	public UptimeProperties(long sampleIntervalMs, long flushIntervalMs, long maxRangeSeconds,
			long defaultRangeSeconds, int maxBufferedWindows, int persistenceBatchSize,
			long retryInitialMs, long retryMaxMs) {
		this(sampleIntervalMs, flushIntervalMs, maxRangeSeconds, defaultRangeSeconds, maxBufferedWindows,
				persistenceBatchSize, retryInitialMs, retryMaxMs, 50);
	}

	public UptimeProperties(long sampleIntervalMs, long flushIntervalMs, long maxRangeSeconds,
			long defaultRangeSeconds) {
		this(sampleIntervalMs, flushIntervalMs, maxRangeSeconds, defaultRangeSeconds, 3600, 60, 1000, 30000);
	}
}
