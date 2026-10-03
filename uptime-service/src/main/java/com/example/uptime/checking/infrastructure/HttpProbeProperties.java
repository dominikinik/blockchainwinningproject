package com.example.uptime.checking.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Field names are literal top-level JSON keys, not dot paths. reasonField must identify
 * a stable error identity, never a diagnostic message or sensitive response field. */
@ConfigurationProperties(prefix = "uptime.probe")
public record HttpProbeProperties(URI url, @DefaultValue("1000") long timeoutMs,
		@DefaultValue("status") String statusField, @DefaultValue("UP") String healthyValue,
		@DefaultValue("DOWN") String unhealthyValue, String reasonField) {
	public HttpProbeProperties {
		if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
		if (statusField == null || statusField.isBlank()) throw new IllegalArgumentException("statusField is required");
		if (healthyValue == null || unhealthyValue == null || healthyValue.equals(unhealthyValue))
			throw new IllegalArgumentException("distinct health values are required");
		if (reasonField != null && reasonField.isBlank()) reasonField = null;
		if (url != null && ((!"http".equalsIgnoreCase(url.getScheme()) && !"https".equalsIgnoreCase(url.getScheme()))
				|| url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null))
			throw new IllegalArgumentException("url must be an HTTP(S) URI without credentials or fragment");
	}

	public Duration timeout() { return Duration.ofMillis(timeoutMs); }
}
