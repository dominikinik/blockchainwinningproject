package com.example.monitor.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.deal.DealAlreadyRegisteredException;
import com.example.monitor.domain.deal.DealChain;
import com.example.monitor.domain.deal.DealChain.ChainDeal;
import com.example.monitor.domain.deal.DealChain.Closure;
import com.example.monitor.domain.deal.DealChain.FoundClosure;
import com.example.monitor.domain.deal.DealChain.TxStatus;
import com.example.monitor.domain.deal.UptimeDeal;
import com.example.monitor.domain.deal.UptimeDealRepository;

/**
 * The monitor as the oracle of the {@code uptime_deal} program. A payer proposes a deal on chain naming this
 * monitor's key as its oracle and registers it here, usually while it is still a proposal, linked to the service
 * that {@link HealthRelay} relays. {@link #settleDue} re-reads a proposal until the recipient accepts it (or a party cancels it); the
 * window comes from the accepted deal account ({@code starts_at}, set by the chain at acceptance), never from the
 * caller. Health-check results are reported to the program as they are produced:
 * <ul>
 * <li>{@link #onHealthResult}: sends the UP/DOWN result for the completed round of every accepted deal; a failed send
 * is retried from memory on the next result or tick.</li>
 * <li>{@link #settleDue}: follows proposal acceptance, asks the program to settle after expiry or proven breach, and
 * confirms sent transactions.</li>
 * </ul>
 * Methods are synchronized: deals change from the check threads, the scheduler and HTTP requests.
 */
public class DealService implements HealthListener {

	private static final Logger log = LoggerFactory.getLogger(DealService.class);

	/**
	 * @param serviceId             the service the relay checks; every deal is linked to it
	 * @param maxDurationSeconds    longest on-chain window accepted
	 * @param settleGraceSeconds    how long after a window ends before it is settled, for the last check to land
	 * @param maxSettleAttempts     failed sends after which a deal is {@code FAILED}
	 * @param confirmTimeoutSeconds how long to wait for a sent settlement before sending it again
	 * @param monitorCheckIntervalSeconds the sampling interval; deal rounds must use this same interval
	 */
	public record Settings(ServiceId serviceId, long maxDurationSeconds, long settleGraceSeconds,
			int maxSettleAttempts, long confirmTimeoutSeconds, long monitorCheckIntervalSeconds) {
	}

	/** Where a wallet must create a deal for this oracle to settle it. */
	public record Config(String programId, String oracle, String rpcUrl, long checkIntervalSeconds) {
	}

	private final UptimeDealRepository deals;

	private final DealChain chain;

	private final Clock clock;

	private final Settings settings;

	private final List<PendingObservation> pendingObservations = new ArrayList<>();

	private record PendingObservation(ServiceId serviceId, Instant observedAt, boolean up) {
	}

	public DealService(UptimeDealRepository deals, DealChain chain, Clock clock, Settings settings) {
		this.deals = deals;
		this.chain = chain;
		this.clock = clock;
		this.settings = settings;
	}

	public Config config() {
		return new Config(chain.programId(), chain.oracleAddress(), chain.rpcUrl(), settings.monitorCheckIntervalSeconds());
	}

	/**
	 * Starts settling an on-chain deal from the relayed service's health results.
	 *
	 * @param serviceId the service whose uptime the deal pays for, or {@code null} for the relayed one
	 * @return the deal: {@code PROPOSED} with no window while the recipient hasn't accepted, or {@code ACTIVE}
	 * @throws IllegalArgumentException       if the address or service is missing or invalid, the account isn't a
	 *                                        deal of the program, names another oracle, or has a duration of 0 or
	 *                                        above the maximum
	 * @throws DealAlreadyRegisteredException if the deal is already registered
	 */
	public synchronized UptimeDeal register(String address, ServiceId serviceId) {
		if (address == null || address.isBlank()) {
			throw new IllegalArgumentException("address is required");
		}
		ServiceId service = serviceId != null ? serviceId : settings.serviceId();
		if (!service.equals(settings.serviceId())) {
			throw new IllegalArgumentException(
					"Service " + service + " is not relayed by this monitor, which relays " + settings.serviceId());
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
		if (deal.checkIntervalSeconds() != settings.monitorCheckIntervalSeconds()) {
			throw new IllegalArgumentException("Deal round interval of " + deal.checkIntervalSeconds()
					+ " seconds must match this monitor's " + settings.monitorCheckIntervalSeconds() + " second check interval");
		}
		chain.ensureOracleFunded();
		UptimeDeal tracked = UptimeDeal.register(address, service, deal.payer(), deal.recipient(),
				deal.amountLamports(), deal.guaranteeLamports(), deal.durationSeconds(), deal.acceptDeadline(),
				deal.startsAt(), clock.instant());
		deals.add(tracked);
		if (deal.accepted()) {
			log.info("Settling deal {} of service {} from {} to {}", address, service, tracked.startsAt(),
					tracked.endsAt());
		}
		else {
			log.info("Watching proposal {} of service {} until its recipient accepts it", address, service);
		}
		return tracked;
	}

	/** @throws NoSuchElementException if the deal isn't registered */
	public UptimeDeal get(String address) {
		return deals.find(address).orElseThrow(() -> new NoSuchElementException("Deal " + address + " is not registered"));
	}

	/** Every deal, most recently proposed first. */
	public List<UptimeDeal> list() {
		return deals.findAll();
	}

	/** Each health result is reported directly to the chain, for the round that ended before it was observed. */
	@Override
	public synchronized void onHealthResult(Instant observedAt, boolean up) {
		pendingObservations.add(new PendingObservation(settings.serviceId(), observedAt, up));
		flushObservations();
	}

	/**
	 * Advances every unfinished deal: picks up the acceptance (or cancellation) of a proposal, and for an active
	 * deal decides it when its window is over, sends a decided settlement, or checks on a sent one. One deal's
	 * failure never stops the others.
	 */
	public synchronized void settleDue() {
		Instant now = clock.instant();
		flushObservations();
		for (UptimeDeal deal : deals.findUnfinished()) {
			if (deal.status() == UptimeDeal.Status.PROPOSED) {
				UptimeDeal next = checkAccepted(deal);
				if (!next.equals(deal)) {
					deals.update(next);
				}
				continue;
			}
			UptimeDeal next = deal;
			try {
				if (deal.signature() != null) {
					next = checkSent(deal, now);
				}
				else {
					ChainDeal account = chain.readDeal(deal.address());
					if (account == null) {
						next = closedOnChain(deal);
					}
					else {
							if (account.accepted() && (account.canSettleEarly()
									|| !now.isBefore(deal.endsAt().plusSeconds(Math.max(settings.settleGraceSeconds(), 10))))) {
								next = send(deal, account, now);
							}
					}
				}
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

	/** Retries live observations from memory; history rows are never consulted to reconstruct a round. */
	private void flushObservations() {
		for (PendingObservation observation : List.copyOf(pendingObservations)) {
			boolean retry = false;
			for (UptimeDeal tracked : deals.findActive(observation.serviceId())) {
				try {
					ChainDeal account = chain.readDeal(tracked.address());
					if (account == null || !account.accepted()) {
						continue;
					}
					long elapsedMillis = Duration.between(account.startsAt(), observation.observedAt()).toMillis();
					long intervalMillis = account.checkIntervalSeconds() * 1_000;
					long round = Math.floorDiv(elapsedMillis, intervalMillis) - 1;
					if (round < 0 || round >= account.totalRounds() || account.isRecorded((int) round)) {
						continue;
					}
					chain.recordObservation(tracked.address(), account, (int) round, observation.up());
					log.info("Recorded deal {} round {} as {}", tracked.address(), round,
							observation.up() ? "UP" : "DOWN");
				}
				catch (RuntimeException e) {
					retry = true;
					log.warn("Could not report {} observation for deal {}: {}", observation.up() ? "UP" : "DOWN",
							tracked.address(), e.getMessage());
				}
			}
			if (!retry) {
				pendingObservations.remove(observation);
			}
		}
	}

	/**
	 * Re-reads a proposal: accepted (the window starts at the acceptance), closed (withdrawn or rejected), or still
	 * waiting. A read failure leaves it waiting for the next tick, since nothing is due yet.
	 */
	private UptimeDeal checkAccepted(UptimeDeal deal) {
		try {
			ChainDeal account = chain.readDeal(deal.address());
			if (account == null) {
				return closedOnChain(deal);
			}
			if (!account.accepted()) {
				return deal;
			}
			UptimeDeal accepted = deal.accepted(account.startsAt());
			log.info("Deal {} was accepted; settling it from {} to {}", deal.address(), accepted.startsAt(),
					accepted.endsAt());
			return accepted;
		}
		catch (RuntimeException e) {
			log.warn("Checking proposal {} failed: {}", deal.address(), e.getMessage());
			return deal;
		}
	}

	private UptimeDeal send(UptimeDeal deal, ChainDeal account, Instant now) {
		if (!account.canSettleEarly()
				&& now.isBefore(deal.endsAt().plusSeconds(Math.max(settings.settleGraceSeconds(), 10)))) {
			return deal;
		}
		String signature = chain.sendSettle(deal.address(), account);
		log.info("Settling deal {} from on-chain counters {}/{} UP rounds: {}", deal.address(), account.upChecks(),
				account.totalRounds(), signature);
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
		return deal.settledBy(found.signature(), closure.paidToRecipient(), closure.upChecks(), closure.totalRounds());
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
