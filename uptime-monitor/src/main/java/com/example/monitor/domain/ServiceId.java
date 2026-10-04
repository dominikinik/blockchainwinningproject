package com.example.monitor.domain;

import java.util.Objects;
import java.util.UUID;

/** Identity of a tracked service: the UUID every one of its events is recorded under. */
public record ServiceId(UUID value) {

	public ServiceId {
		Objects.requireNonNull(value, "value");
	}

	public static ServiceId newId() {
		return new ServiceId(UUID.randomUUID());
	}

	public static ServiceId of(String value) {
		return new ServiceId(UUID.fromString(value));
	}

	@Override
	public String toString() {
		return value.toString();
	}

}
