package com.example.monitor.infrastructure.config;

import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

public final class HttpTimeouts {

	private HttpTimeouts() {
	}

	/**
	 * Builds a request factory whose calls fail instead of hanging.
	 *
	 * @param timeout connect and read timeout
	 */
	public static SimpleClientHttpRequestFactory timeouts(Duration timeout) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(timeout);
		factory.setReadTimeout(timeout);
		return factory;
	}

}
