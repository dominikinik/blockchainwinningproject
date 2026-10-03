package com.example.monitor.infrastructure.probe;

import java.net.URI;
import java.util.Map;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthProbe;

/**
 * {@link HealthProbe} over HTTP GET. Reads the {@code status} field of a JSON body (the shape of both
 * Spring Boot's {@code /actuator/health} and {@code uptime-service}'s {@code /api/health}).
 */
public class HttpHealthProbe implements HealthProbe {

	private final RestClient client;

	/** @param builder a {@link RestClient} builder, configured with connect and read timeouts */
	public HttpHealthProbe(RestClient.Builder builder) {
		this.client = builder.build();
	}

	@Override
	public HealthCheckResult check(String healthUrl) {
		try {
			HealthCheckResult result = client.get().uri(URI.create(healthUrl)).exchange((request, response) -> {
				int status = response.getStatusCode().value();
				return HealthCheckResult.fromResponse(status, status == 200 ? reportedStatus(response) : null);
			});
			return result != null ? result : HealthCheckResult.unreachable("No response");
		}
		catch (RestClientException | IllegalArgumentException e) {
			return HealthCheckResult.unreachable(e.getMessage());
		}
	}

	private static String reportedStatus(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
		try {
			Map<?, ?> body = response.bodyTo(Map.class);
			return body != null && body.get("status") instanceof String s ? s : null;
		}
		catch (RestClientException e) {
			// Not JSON: the 200 alone says the service is up.
			return null;
		}
	}

}
