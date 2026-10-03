package com.example.uptime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UptimePropertiesTest {
	@Test
	void convenienceConstructorSuppliesBufferBatchAndRetryDefaults() {
		UptimeProperties properties = new UptimeProperties(10, 1000, 3600, 300);
		assertEquals(new UptimeProperties(10, 1000, 3600, 300, 3600, 60, 1000, 30000), properties);
		assertEquals(10, properties.sampleIntervalMs());
		assertEquals(1000, properties.flushIntervalMs());
		assertEquals(3600, properties.maxRangeSeconds());
		assertEquals(300, properties.defaultRangeSeconds());
		assertEquals(3600, properties.maxBufferedWindows());
		assertEquals(60, properties.persistenceBatchSize());
		assertEquals(1000, properties.retryInitialMs());
		assertEquals(30000, properties.retryMaxMs());
	}

	@Test
	void eachNumericSettingRejectsZeroAndNegativeValues() {
		String[] names = {"sampleIntervalMs", "flushIntervalMs", "maxRangeSeconds", "defaultRangeSeconds",
				"maxBufferedWindows", "persistenceBatchSize", "retryInitialMs", "retryMaxMs"};
		for (int index = 0; index < names.length; index++) {
			for (long invalid : new long[] {0, -1}) {
				long[] values = {10, 1000, 3600, 300, 3600, 60, 1000, 30000};
				values[index] = invalid;
				assertThrows(IllegalArgumentException.class, () -> properties(values),
						names[index] + " must reject " + invalid);
			}
		}
	}

	@Test
	void rangeMustFitListCapacityAndDefaultMustNotExceedMaximum() {
		assertThrows(IllegalArgumentException.class,
				() -> new UptimeProperties(10, 1000, (long) Integer.MAX_VALUE + 1, 300));
		assertThrows(IllegalArgumentException.class, () -> new UptimeProperties(10, 1000, 299, 300));
		assertDoesNotThrow(() -> new UptimeProperties(10, 1000, Integer.MAX_VALUE, Integer.MAX_VALUE));
	}

	@Test
	void retryMaximumMustNotBeLessThanInitialDelay() {
		assertThrows(IllegalArgumentException.class,
				() -> new UptimeProperties(10, 1000, 3600, 300, 3600, 60, 1000, 999));
		assertDoesNotThrow(() -> new UptimeProperties(10, 1000, 3600, 300, 3600, 60, 1000, 1000));
	}

	@Test
	void minimumPositiveSettingsAndEqualRangeBoundsAreValid() {
		assertDoesNotThrow(() -> new UptimeProperties(1, 1, 1, 1, 1, 1, 1, 1));
	}

	@Test
	void observationGapDefaultsAndValidationApplyToAllConstructors() {
		assertEquals(50000, new UptimeProperties(10, 1000, 3600, 300).maxObservationGapMs());
		assertThrows(IllegalArgumentException.class,
				() -> new UptimeProperties(10, 1000, 3600, 300, 3600, 60, 1000, 30000, 0));
		assertThrows(IllegalArgumentException.class,
				() -> new UptimeProperties(10, 1000, 3600, 300, 3600, 60, 1000, 30000, -1));
		assertEquals(100, new UptimeProperties(10, 1000, 3600, 300, 3600, 60, 1000, 30000, 100).maxObservationGapMs());
	}

	@Test
	void convenienceConstructorStillAppliesCanonicalValidation() {
		assertThrows(IllegalArgumentException.class, () -> new UptimeProperties(0, 1000, 3600, 300));
		assertThrows(IllegalArgumentException.class, () -> new UptimeProperties(10, 0, 3600, 300));
		assertThrows(IllegalArgumentException.class, () -> new UptimeProperties(10, 1000, 3600, 0));
	}

	private static UptimeProperties properties(long[] values) {
		return new UptimeProperties(values[0], values[1], values[2], values[3],
				(int) values[4], (int) values[5], values[6], values[7]);
	}
}
