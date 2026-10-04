package com.example.monitor.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEventStore;
import com.example.monitor.domain.deal.DealAlreadyRegisteredException;
import com.example.monitor.domain.deal.DealChain;
import com.example.monitor.domain.deal.DealChain.ChainDeal;
import com.example.monitor.domain.deal.DealChain.Closure;
import com.example.monitor.domain.deal.DealChain.FoundClosure;
import com.example.monitor.domain.deal.DealChain.TxStatus;
import com.example.monitor.domain.deal.DealMeasurement;
import com.example.monitor.domain.deal.SettlementPolicy;
import com.example.monitor.domain.deal.UptimeDeal;
import com.example.monitor.domain.deal.UptimeDealRepository;
import com.example.monitor.domain.deal.Verdict;

/**
 * The monitor as the oracle of the {@code uptime_deal} program. A wallet creates a deal on chain naming this
 * monitor's key as its oracle and registers it here, linked to a tracked service. The window comes from the
 * deal account, never from the caller. Deals are settled from the service's events:
 * <ul>
 * <li>{@link #onTrackingEvent}: after a {@code Downtime}, {@code InternalErrorHappened} or
 * {@code TrackingFinished} of the service, {@link SettlementPolicy} may decide the settlement at once: a failure
 * that makes 99% unreachable closes the deal (refund), and the end of tracking settles it.</li>
 * <li>{@link #settleDue}: decides deals whose window is over, sends decided settlements, and confirms sent ones
 * (resending lost ones and explaining vanished accounts from their history).</li>
 * </ul>
 * Methods are synchronized: deals change from the check threads, the scheduler and HTTP requests.
 */
public class DealService implements TrackingEventListener {

	private static final Logger log = LoggerFactory.getLogger(DealService.class);

	/**
	 * @param defaultServiceId      the service a deal is linked to when registered without one; may be {@code null}
	 * @param maxDurationSeconds    longest on-chain window accepted
	 * @param settleGraceSeconds    how long after a window ends before it is settled, for the last check to land
	 * @param maxSettleAttempts     failed sends after which a deal is {@code FAILED}
	 * @param confirmTimeoutSeconds how long to wait for a sent settlement before sending it again
	 */
	public record Settings(ServiceId defaultServiceId, long maxDurationSeconds, long settleGraceSeconds,
			int maxSettleAttempts, long confirmTimeoutSeconds) {
	}

	/** Where a wallet must create a deal for this oracle to settle it. */
	public record Config(String programId, String oracle, String rpcUrl) {
	}

	private final UptimeDealRepository deals;

	private final DealChain chain;

	private final TrackingEventStore events;

	private final Clock clock;

	private final Settings settings;

	public DealService(UptimeDealRepository deals, DealChain chain, TrackingEventStore events, Clock clock,
			Settings settings) {
		this.deals = deals;
		this.chain = chain;
		this.events = events;
		this.clock = clock;
		this.settings = settings;
	}

	public Config config() {
		return new Config(chain.programId(), chain.oracleAddress(), chain.rpcUrl());
	}

	/**
	 * Starts settling an on-chain deal from a tracked service's events.
	 *
	 * @param serviceId the service whose uptime the deal pays for, or {@code null} for the default service
	 * @throws IllegalArgumentException       if the address or service is missing or invalid, the account isn't a
	 *                                        deal of the program, names another oracle, or has a duration of 0 or
	 *                                        above the maximum
	 * @throws DealAlreadyRegisteredException if the deal is already registered
	 */
	public synchronized UptimeDeal register(String address, ServiceId serviceId) {
		if (address == null || address.isBlank()) {
			throw new IllegalArgumentException("address is required");
		}
		ServiceId service = serviceId != null ? serviceId : settings.defaultServiceId();
		if (service == null) {
			throw new IllegalArgumentException("serviceId is required: no default service is configured");
		}
		if (events.load(service).isEmpty()) {
			throw new IllegalArgumentException("Service " + service + " is not tracked by this monitor");
		}
		if (deals.find(address).isPresent()) {
			throw new DealAlreadyRegisteredException(address);
		}
		ChainDeal deal = chain.readDeal(address);
		if (deal == null) {
			throw new IllegalArgumentException("No uptime_deal account exists at " + address);
		}
		if (!deal.oracle().equals(chain.oracleAddress())) {
			throw new IllegalArgumentException(
					"Deal names oracle " + deal.oracle() + ", but this monitor is " + chain.oracleAddress());
		}
		if (deal.durationSeconds() < 1 || deal.durationSeconds() > settings.maxDurationSeconds()) {
			throw new IllegalArgumentException("Deal's on-chain duration of " + deal.durationSeconds()
					+ " seconds must be between 1 and " + settings.maxDurationSeconds());
		}
		chain.ensureOracleFunded();
		UptimeDeal tracked = UptimeDeal.register(address, service, deal.payer(), deal.recipient(),
				deal.amountLamports(), deal.durationSeconds(), deal.startsAt(), clock.instant());
		deals.add(tracked);
		log.info("Settling deal {} of service {} from {} to {}", address, service, tracked.startsAt(),
				tracked.endsAt());
		return tracked;
	}

	/** @throws NoSuchElementException if the deal isn't registered */
	public UptimeDeal get(String address) {
		return deals.find(address).orElseThrow(() -> new NoSuchElementException("Deal " + address + " is not registered"));
	}

	/** Every deal, newest window first. */
	public List<UptimeDeal> list() {
		return deals.findAll();
	}

	/** Decides the open deals of the event's service that the event closes. Sending happens in {@link #settleDue}. */
	@Override
	public synchronized void onTrackingEvent(TrackingEvent event) {
		List<UptimeDeal> open = deals.findActive(event.serviceId()).stream().filter(UptimeDeal::isOpen).toList();
		if (open.isEmpty()) {
			return;
		}
		List<TrackingEvent> history = events.load(event.serviceId());
		for (UptimeDeal deal : open) {
			if (event.occurredAt().isBefore(deal.startsAt())) {
				continue;
			}
			DealMeasurement m = DealMeasurement.of(history, deal.startsAt(), deal.durationSeconds(), event.occurredAt());
			Optional<Verdict> verdict = SettlementPolicy.onEvent(event, m);
			if (verdict.isPresent()) {
				log.info("{} of {} closes deal {}: {}/{} up seconds", event.type(), event.serviceId(), deal.address(),
						verdict.get().upSeconds(), verdict.get().totalSeconds());
				deals.update(deal.decide(verdict.get()));
			}
		}
	}

	/**
	 * Advances every active deal: decides it when its window is over, sends a decided settlement, or checks on a
	 * sent one. One deal's failure never stops the others.
	 */
	public synchronized void settleDue() {
		Instant now = clock.instant();
		for (UptimeDeal deal : deals.findActive()) {
			UptimeDeal next = deal;
			try {
				if (deal.isOpen()) {
					if (now.isBefore(deal.endsAt().plusSeconds(settings.settleGraceSeconds()))) {
						continue;
					}
					DealMeasurement m = DealMeasurement.of(events.load(deal.serviceId()), deal.startsAt(),
							deal.durationSeconds(), now);
					next = deal.decide(SettlementPolicy.atWindowEnd(m));
					deals.update(next);
				}
				next = next.signature() == null ? send(next, now) : checkSent(next, now);
			}
			catch (RuntimeException e) {
				log.warn("Settling deal {} failed: {}", deal.address(), e.getMessage());
				next = next.failedAttempt(e.getMessage(), settings.maxSettleAttempts());
			}
			if (!next.equals(deal)) {
				deals.update(next);
			}
		}
	}

	private UptimeDeal send(UptimeDeal deal, Instant now) {
		ChainDeal account = chain.readDeal(deal.address());
		if (account == null) {
			return closedOnChain(deal);
		}
		Verdict verdict = new Verdict(deal.upSeconds(), deal.totalSeconds());
		String signature = chain.sendSettle(deal.address(), account, verdict);
		log.info("Settling deal {} with {}/{} up seconds: {}", deal.address(), verdict.upSeconds(),
				verdict.totalSeconds(), signature);
		return deal.sent(signature, now);
	}

	/** The account is gone: it was settled (by a send that threw after landing) or cancelled by the payer. */
	private UptimeDeal closedOnChain(UptimeDeal deal) {
		FoundClosure found = chain.findClosure(deal.address());
		if (found == null) {
			return deal.failed("The deal account no longer exists and no DealSettled or DealCancelled event for it "
					+ "was found in its recent transactions");
		}
		Closure closure = found.closure();
		if (closure.cancelled()) {
			log.info("Deal {} was cancelled by its payer: {}", deal.address(), found.signature());
			return deal.cancelled(found.signature());
		}
		log.info("Deal {} was settled by {}, paid to recipient: {}", deal.address(), found.signature(),
				closure.paidToRecipient());
		return deal.settledBy(found.signature(), closure.paidToRecipient(), closure.upSeconds(), closure.totalSeconds());
	}

	private UptimeDeal checkSent(UptimeDeal deal, Instant now) {
		TxStatus status = chain.status(deal.signature());
		if (status == null || !status.confirmed()) {
			if (now.isAfter(deal.sentAt().plusSeconds(settings.confirmTimeoutSeconds()))) {
				return deal.failedAttempt("Settlement " + deal.signature() + " was not confirmed in time",
						settings.maxSettleAttempts());
			}
			return deal;
		}
		if (status.error() != null) {
			return deal.failed("Settlement transaction failed: " + status.error());
		}
		Closure closure = chain.closureIn(deal.signature(), deal.address());
		Boolean paid = closure == null || closure.cancelled() ? null : closure.paidToRecipient();
		log.info("Deal {} settled, paid to recipient: {}", deal.address(), paid);
		return deal.settled(paid);
	}

}
