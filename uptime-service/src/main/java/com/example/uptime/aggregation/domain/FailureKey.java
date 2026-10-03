package com.example.uptime.aggregation.domain;

import java.util.Objects;

public record FailureKey(BadEventType type, String code, String reason) {
    public FailureKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(code, "code");
        reason = reason == null ? "" : reason;
        if (code.isBlank()) throw new IllegalArgumentException("Failure code must not be blank");
    }
}
