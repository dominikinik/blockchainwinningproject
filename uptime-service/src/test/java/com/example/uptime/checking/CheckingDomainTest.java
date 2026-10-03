package com.example.uptime.checking;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.uptime.checking.domain.CheckFailure;
import com.example.uptime.checking.domain.CheckOutcome;
import com.example.uptime.checking.domain.CheckResult;
import com.example.uptime.checking.domain.ProbeResult;

import static org.junit.jupiter.api.Assertions.*;

class CheckingDomainTest {
	private static final UUID ID = UUID.randomUUID();
	private static final Instant TIME = Instant.parse("2026-10-03T12:00:00Z");
	private static final CheckFailure FAILURE = new CheckFailure("DOWN", "Unavailable");

	@Test
	void failureRequiresCodeAndMessageAndBoundsMessage() {
		assertThrows(NullPointerException.class, () -> new CheckFailure(null, "message"));
		assertThrows(NullPointerException.class, () -> new CheckFailure("DOWN", null));
		assertThrows(IllegalArgumentException.class, () -> new CheckFailure(" \t", "message"));
		assertEquals("x".repeat(512), new CheckFailure("DOWN", "x".repeat(600)).message());
		assertEquals("x".repeat(511), new CheckFailure("DOWN", "x".repeat(511) + "😀").message());
	}

	@Test
	void failureTypeAndStableReasonAreRequiredAndBounded() {
		var type = com.example.uptime.checking.domain.FailureType.DOWNTIME;
		assertEquals(com.example.uptime.checking.domain.FailureType.CHECK_FAILURE, FAILURE.type());
		assertEquals("", FAILURE.reason());
		assertThrows(NullPointerException.class, () -> new CheckFailure("DOWN", "message", null, ""));
		assertThrows(NullPointerException.class, () -> new CheckFailure("DOWN", "message", type, null));
		String first = new CheckFailure("DOWN", "message", type, "x".repeat(300) + "A").reason();
		String second = new CheckFailure("DOWN", "message", type, "x".repeat(300) + "B").reason();
		assertTrue(first.startsWith("sha256:"));
		assertTrue(first.length() <= 256);
		assertNotEquals(first, second);
		assertEquals(first, new CheckFailure("DOWN", "other message", type, "x".repeat(300) + "A").reason());
		assertEquals(new CheckFailure("DOWN", "message", type, "application"),
				ProbeResult.failure("DOWN", "message", type, "application").failure());
	}

	@Test
	void probeResultEnforcesOutcomeFailureConsistency() {
		assertThrows(NullPointerException.class, () -> new ProbeResult(null, null));
		assertThrows(IllegalArgumentException.class, () -> new ProbeResult(CheckOutcome.SUCCESS, FAILURE));
		assertThrows(IllegalArgumentException.class, () -> new ProbeResult(CheckOutcome.FAILURE, null));
		assertEquals(new ProbeResult(CheckOutcome.SUCCESS, null), ProbeResult.success());
		assertEquals(new ProbeResult(CheckOutcome.FAILURE, FAILURE), ProbeResult.failure("DOWN", "Unavailable"));
	}

	@Test
	void checkResultRequiresAllMandatoryFields() {
		assertThrows(NullPointerException.class, () -> new CheckResult(null, TIME, TIME, 0, CheckOutcome.SUCCESS, null));
		assertThrows(NullPointerException.class, () -> new CheckResult(ID, null, TIME, 0, CheckOutcome.SUCCESS, null));
		assertThrows(NullPointerException.class, () -> new CheckResult(ID, TIME, null, 0, CheckOutcome.SUCCESS, null));
		assertThrows(NullPointerException.class, () -> new CheckResult(ID, TIME, TIME, 0, null, null));
	}

	@Test
	void checkResultEnforcesDurationTimestampAndFailureInvariants() {
		assertThrows(IllegalArgumentException.class, () -> new CheckResult(ID, TIME, TIME, -1, CheckOutcome.SUCCESS, null));
		assertThrows(IllegalArgumentException.class, () -> new CheckResult(ID, TIME, TIME.minusNanos(1), 0, CheckOutcome.SUCCESS, null));
		assertThrows(IllegalArgumentException.class, () -> new CheckResult(ID, TIME, TIME, 0, CheckOutcome.SUCCESS, FAILURE));
		assertThrows(IllegalArgumentException.class, () -> new CheckResult(ID, TIME, TIME, 0, CheckOutcome.FAILURE, null));
		assertDoesNotThrow(() -> new CheckResult(ID, TIME, TIME, 0, CheckOutcome.SUCCESS, null));
		assertDoesNotThrow(() -> new CheckResult(ID, TIME, TIME.plusNanos(1), 1, CheckOutcome.FAILURE, FAILURE));
	}
}
