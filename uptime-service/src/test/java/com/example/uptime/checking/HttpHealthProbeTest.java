package com.example.uptime.checking;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.example.uptime.checking.domain.*;
import com.example.uptime.checking.infrastructure.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HttpHealthProbeTest {
	private final HttpClient client = mock(HttpClient.class);
	private HttpProbeProperties properties = new HttpProbeProperties(URI.create("https://example.test/health"),
			1000, "status", "UP", "DOWN", null);

	private HttpHealthProbe probe() {
		when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
		return new HttpHealthProbe(client, properties, new ObjectMapper());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void response(int status, String body) {
		when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
			HttpRequest request = call.getArgument(0);
			assertEquals(properties.timeout(), request.timeout().orElseThrow());
			HttpResponse.BodyHandler<byte[]> handler = call.getArgument(1);
			HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
			when(info.statusCode()).thenReturn(status);
			HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(info);
			subscriber.onSubscribe(mock(Flow.Subscription.class));
			subscriber.onNext(List.of(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
			return subscriber.getBody().toCompletableFuture().thenApply(bytes -> {
				HttpResponse<byte[]> result = mock(HttpResponse.class);
				when(result.statusCode()).thenReturn(status);
				when(result.body()).thenReturn(bytes);
				return result;
			});
		});
	}

	@Test void classifiesExplicitHealthOnly() {
		response(200, "{\"status\":\"UP\"}");
		assertEquals(ProbeResult.success(), probe().check());
		response(200, "{\"status\":\"DOWN\",\"password\":\"secret\"}");
		CheckFailure failure = probe().check().failure();
		assertEquals(FailureType.DOWNTIME, failure.type());
		assertEquals("HEALTH_DOWN", failure.code());
		assertEquals("", failure.reason());
		assertFalse(failure.message().contains("secret"));
	}

	@Test void rejectsMalformedUnrecognizedAndNonScalarHealth() {
		for (String body : List.of("broken secret", "{}", "{\"status\":\"UNKNOWN\"}",
				"{\"status\":null}", "{\"status\":{}}", "{\"status\":[]}",
				"{\"status\":\"UP\"} {}", "x".repeat(HttpHealthProbe.MAX_BODY_BYTES + 1))) {
			response(200, body);
			assertEquals("INVALID_HEALTH_RESPONSE", probe().check().failure().code());
			assertEquals(FailureType.CHECK_FAILURE, probe().check().failure().type());
		}
	}

	@Test void distinguishesHttpStatusesAndDoesNotFollowRedirects() {
		for (int status : List.of(500, 503, 302)) {
			response(status, "secret");
			CheckFailure failure = probe().check().failure();
			assertEquals("HTTP_STATUS", failure.code());
			assertEquals(Integer.toString(status), failure.reason());
			assertEquals(FailureType.CHECK_FAILURE, failure.type());
		}
		when(client.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
		assertThrows(IllegalArgumentException.class, () -> new HttpHealthProbe(client, properties, new ObjectMapper()));
	}

	@Test void supportsConfiguredScalarStatusAndBoundedStableReason() {
		properties = new HttpProbeProperties(properties.url(), 1000, "type", "true", "false", "identity");
		response(200, "{\"type\":true}");
		assertEquals(ProbeResult.success(), probe().check());
		response(200, "{\"type\":false,\"identity\":\"" + "x".repeat(300) + "\"}");
		assertEquals(new CheckFailure("HEALTH_DOWN", "message", FailureType.DOWNTIME, "x".repeat(300)).reason(),
						probe().check().failure().reason());
		response(200, "{\"type\":false}");
		assertEquals("INVALID_HEALTH_RESPONSE", probe().check().failure().code());
	}

	@Test void transportErrorsHaveStableSanitizedCodes() {
		for (Exception error : List.of(new HttpTimeoutException("secret"), new SSLException("secret"),
				new java.net.ConnectException("secret"))) {
			when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
					.thenReturn(CompletableFuture.failedFuture(error));
			CheckFailure failure = probe().check().failure();
			String code = error instanceof HttpTimeoutException ? "HTTP_TIMEOUT"
					: error instanceof SSLException ? "HTTP_TLS_FAILURE" : "HTTP_NETWORK_FAILURE";
			assertEquals(code, failure.code());
			assertEquals(FailureType.CHECK_FAILURE, failure.type());
			assertFalse(failure.message().contains("secret"));
		}
	}

	@Test void fullResponseDeadlineCancelsPendingRequest() {
		properties = new HttpProbeProperties(properties.url(), 1, "status", "UP", "DOWN", null);
		CompletableFuture<HttpResponse<Object>> pending = new CompletableFuture<>();
		when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
		assertEquals("HTTP_TIMEOUT", probe().check().failure().code());
		assertTrue(pending.isCancelled());
	}

	@Test void interruptionRestoresFlagAndCancelsRequest() {
		CompletableFuture<HttpResponse<Object>> pending = new CompletableFuture<>();
		when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
		Thread.currentThread().interrupt();
		try {
			assertEquals("HTTP_INTERRUPTED", probe().check().failure().code());
			assertTrue(Thread.currentThread().isInterrupted());
			assertTrue(pending.isCancelled());
		}
		finally { Thread.interrupted(); }
	}

	@Test void validatesProperties() {
		assertThrows(IllegalArgumentException.class, () -> new HttpProbeProperties(null, 0, "status", "UP", "DOWN", null));
		assertThrows(IllegalArgumentException.class, () -> new HttpProbeProperties(URI.create("file:///secret"), 1, "status", "UP", "DOWN", null));
		assertThrows(IllegalArgumentException.class, () -> new HttpProbeProperties(null, 1, "status", "UP", "UP", null));
		assertNull(new HttpProbeProperties(null, 1000, "status", "UP", "DOWN", null).url());
	}
}
