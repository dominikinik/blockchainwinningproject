package com.example.monitor.domain.deal;

/**
 * The {@code (up_seconds, total_seconds)} pair sent to {@code settle_deal}; the program pays the recipient
 * when it is strictly above 99%.
 */
public record Verdict(long upSeconds, long totalSeconds) {

	public Verdict {
		if (totalSeconds <= 0 || upSeconds < 0 || upSeconds > totalSeconds) {
			throw new IllegalArgumentException("Invalid verdict " + upSeconds + "/" + totalSeconds);
		}
	}

	/** The program's rule, {@code up * 100 > total * 99}, in exact arithmetic. */
	public boolean paysRecipient() {
		return SettlementPolicy.aboveThreshold(upSeconds, totalSeconds);
	}

}
