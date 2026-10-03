package com.example.uptime.checking.infrastructure;

import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.example.uptime.checking.application.HealthProbe;
import com.example.uptime.checking.domain.FailureType;
import com.example.uptime.checking.domain.ProbeResult;

public final class HttpHealthProbe implements HealthProbe {
	public static final int MAX_BODY_BYTES = 64 * 1024;
	private final HttpClient client;
	private final HttpProbeProperties properties;
	private final ObjectMapper mapper;

	public HttpHealthProbe(HttpClient client, HttpProbeProperties properties, ObjectMapper mapper) {
		this.client = Objects.requireNonNull(client, "client");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.mapper = Objects.requireNonNull(mapper, "mapper");
		Objects.requireNonNull(properties.url(), "url is required for HTTP probing");
		if (client.followRedirects() != HttpClient.Redirect.NEVER)
			throw new IllegalArgumentException("HTTP probing requires redirects disabled");
	}

	@Override
	public ProbeResult check() {
		HttpRequest request = HttpRequest.newBuilder(properties.url()).timeout(properties.timeout())
				.header("Accept", "application/json").GET().build();
		CompletableFuture<HttpResponse<byte[]>> pending = null;
		try {
			pending = client.sendAsync(request, info -> info.statusCode() == 200
					? new BoundedBodySubscriber() : HttpResponse.BodySubscribers.replacing(new byte[0]));
			HttpResponse<byte[]> response = pending.get(properties.timeoutMs(), TimeUnit.MILLISECONDS);
			if (response.statusCode() != 200)
				return failure("HTTP_STATUS", "Health endpoint returned a non-200 status", Integer.toString(response.statusCode()));
			return interpret(response.body());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return failure("HTTP_INTERRUPTED", "Health request was interrupted", "");
		}
		catch (TimeoutException exception) {
			return failure("HTTP_TIMEOUT", "Health request timed out", "");
		}
		catch (ExecutionException | RuntimeException exception) {
			for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
				if (cause instanceof BodyLimitException) return invalid();
				if (cause instanceof HttpTimeoutException) return failure("HTTP_TIMEOUT", "Health request timed out", "");
				if (cause instanceof SSLException) return failure("HTTP_TLS_FAILURE", "Health request TLS failed", "");
			}
			return failure("HTTP_NETWORK_FAILURE", "Health request could not be completed", "");
		}
		finally {
			if (pending != null && !pending.isDone()) pending.cancel(true);
		}
	}

	private ProbeResult interpret(byte[] body) {
		if (body == null || body.length > MAX_BODY_BYTES) return invalid();
		try {
			JsonNode root = mapper.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.readTree(body);
			if (root == null || !root.isObject()) return invalid();
			JsonNode status = root.get(properties.statusField());
			if (!scalar(status)) return invalid();
			if (properties.healthyValue().equals(status.asText())) return ProbeResult.success();
			if (!properties.unhealthyValue().equals(status.asText())) return invalid();
			String reason = "";
			if (properties.reasonField() != null) {
				JsonNode value = root.get(properties.reasonField());
				if (!scalar(value)) return invalid();
				reason = value.asText();
			}
			return ProbeResult.failure("HEALTH_DOWN", "Remote health is explicitly unhealthy", FailureType.DOWNTIME, reason);
		}
		catch (RuntimeException exception) { return invalid(); }
	}

	private static boolean scalar(JsonNode node) {
		return node != null && (node.isString() || node.isNumber() || node.isBoolean());
	}
	private static ProbeResult invalid() {
		return failure("INVALID_HEALTH_RESPONSE", "Health response is invalid or unrecognized", "");
	}
	private static ProbeResult failure(String code, String message, String reason) {
		return ProbeResult.failure(code, message, FailureType.CHECK_FAILURE, reason);
	}

	private static final class BodyLimitException extends IOException {}
	private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
		private Flow.Subscription subscription;
		private int received;
		private boolean done;
		@Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
		@Override public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			delegate.onSubscribe(subscription);
		}
		@Override public void onNext(List<ByteBuffer> buffers) {
			if (done) return;
			for (ByteBuffer buffer : buffers) {
				if (buffer.remaining() > MAX_BODY_BYTES - received) {
					done = true;
					subscription.cancel();
					delegate.onError(new BodyLimitException());
					return;
				}
				received += buffer.remaining();
			}
			delegate.onNext(buffers);
		}
		@Override public void onError(Throwable error) { if (!done) { done = true; delegate.onError(error); } }
		@Override public void onComplete() { if (!done) { done = true; delegate.onComplete(); } }
	}
}
