package com.example.monitor.domain.deal;

import java.math.BigInteger;
import java.util.Optional;

import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;

/**
 * When an open deal is settled, and with what:
 * <ul>
 * <li>A failure ({@code Downtime}, {@code InternalErrorHappened}) closes the deal at once when even full uptime
 * for the rest of the window can no longer lift it above 99%; it reports that best case, so the payer is
 * refunded. Otherwise the deal stays open.</li>
 * <li>{@code TrackingFinished} settles at once with the up seconds so far: nothing watches the rest of the
 * window, so it counts as down.</li>
 * <li>The end of the window settles with the up seconds measured over it.</li>
 * </ul>
 */
public final class SettlementPolicy {

	private static final BigInteger HUNDRED = BigInteger.valueOf(100);

	private static final BigInteger NINETY_NINE = BigInteger.valueOf(99);

	private SettlementPolicy() {
	}

	/** The verdict an event forces on an open deal, if any. Other event types never decide. */
	public static Optional<Verdict> onEvent(TrackingEvent event, DealMeasurement m) {
		return switch (event) {
			case Downtime e -> belowThresholdForGood(m);
			case InternalErrorHappened e -> belowThresholdForGood(m);
			case TrackingFinished e -> Optional.of(new Verdict(m.upSoFar(), m.total()));
			default -> Optional.empty();
		};
	}

	/** The verdict once the window is over. */
	public static Verdict atWindowEnd(DealMeasurement m) {
		return new Verdict(m.upSoFar(), m.total());
	}

	/** The program's threshold: {@code up / total} strictly above 99%. */
	public static boolean aboveThreshold(long up, long total) {
		return BigInteger.valueOf(up).multiply(HUNDRED).compareTo(BigInteger.valueOf(total).multiply(NINETY_NINE)) > 0;
	}

	private static Optional<Verdict> belowThresholdForGood(DealMeasurement m) {
		long best = m.bestCaseUp();
		return aboveThreshold(best, m.total()) ? Optional.empty() : Optional.of(new Verdict(best, m.total()));
	}

}
