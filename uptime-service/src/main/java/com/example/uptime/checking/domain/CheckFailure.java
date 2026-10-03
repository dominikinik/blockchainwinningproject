package com.example.uptime.checking.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record CheckFailure(String code, String message, FailureType type, String reason) {
	public CheckFailure(String code, String message) {
		this(code, message, FailureType.CHECK_FAILURE, "");
	}

	public CheckFailure {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(reason, "reason");
		if (code.isBlank()) throw new IllegalArgumentException("code must not be blank");
		message = bounded(message, 512);
		if (reason.length() > 256) {
			// Truncating identity would merge different errors that happen to share a long prefix.
			reason = digest(reason);
		}
	}

	private static String bounded(String value, int limit) {
		if (value.length() <= limit) return value;
		int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
		return value.substring(0, end);
	}

	private static String digest(String value) {
		try {
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("The JVM must support SHA-256", unavailable);
		}
	}
}
