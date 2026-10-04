package com.example.monitor.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.monitor.domain.HealthCheckResult;
import com.example.monitor.domain.HealthProbe;
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
 * monitor's key as its oracle and registers it here, usually while it is still a proposal, linked to the provider
 * whose health endpoint it checks. The window comes from the accepted deal account ({@code starts_at}, set by the
 * chain at acceptance), never from the caller. {@link #settleDue} runs every few hundred milliseconds and:
 * <ul>
 * <li>re-reads a proposal until the recipient accepts it (or a party cancels it);</li>
 * <li>runs each accepted deal's heartbeat at the deal's own on-chain round interval: once a round ends, it calls the
 * provider's health endpoint and sends the UP/DOWN result as that round's observation (one probe per tick serves every
 * deal whose round ended in it); a failed send is retried from memory;</li>
 * <li>asks the program to settle after expiry or proven breach, and confirms sent transactions.</li>
 * </ul>
 * Methods are synchronized: deals change from the scheduler and HTTP requests.
 */
public class DealService {

	private static final Logger log = LoggerFactory.getLogger(DealService.class);

	/**
	 * @param serviceId             the provider this monitor checks; every deal is linked to it
	 * @param healthUrl             the provider's health endpoint, called once per round of each deal
	 * @param maxDurationSeconds    longest on-chain window accepted
	 * @param settleGraceSeconds    how long after a window ends before it is settled, for the last check to land
	 * @param maxSettleAttempts     failed sends after which a deal is {@code FAILED}
	 * @param confirmTimeoutSeconds how long to wait for a sent settlement before sending it again
	 * @param defaultCheckIntervalSeconds the round length suggested to wallets; any interval the program accepts works
	 */
	public record Settings(ServiceId serviceId, String healthUrl, long maxDurationSeconds, long settleGraceSeconds,
			int maxSettleAttempts, long confirmTimeoutSeconds, long defaultCheckIntervalSeconds) {
	}

	/** Where a wallet must create a deal for this oracle to settle it, and a suggested round length. */
	public record Config(String programId, String oracle, String rpcUrl, long checkIntervalSeconds) {
	}

	private final UptimeDealRepository deals;

	private final DealChain chain;

	private final HealthProbe probe;

	private final Clock clock;

	private final Settings settings;

	private final List<PendingObservation> pendingObservations = new ArrayList<>();

	/** The last round whose heartbeat ran, per deal; a restart starts with the next round that ends. */
	private final Map<String, Integer> lastHeartbeat = new HashMap<>();

	private record PendingObservation(String address, int round, boolean up) {
	}

	public DealService(UptimeDealRepository deals, DealChain chain, HealthProbe probe, Clock clock, Settings settings) {
		this.deals = deals;
		this.chain = chain;
		this.probe = probe;
		this.clock = clock;
		this.settings = settings;
	}

	public Config config() {
		return new Config(chain.programId(), chain.oracleAddress(), chain.rpcUrl(), settings.defaultCheckIntervalSeconds());
	}

	/**
	 * Starts checking and settling an on-chain deal, at the deal's own round interval.
	 *
	 * @param serviceId the service whose uptime the deal pays for, or {@code null} for the checked provider
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

	/**
	 * Advances every unfinished deal: picks up the acceptance (or cancellation) of a proposal, and for an active
	 * deal runs its heartbeat when a round has ended, decides it when its window is over, sends a decided settlement,
	 * or checks on a sent one. One deal's failure never stops the others.
	 */
	public synchronized void settleDue() {
		Instant now = clock.instant();
		retryObservations(now);
		Heartbeat heartbeat = new Heartbeat();
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
							if (account.accepted()) {
							beat(deal, account, now, heartbeat);
						}
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

	/** One provider probe per tick, made only when some deal's round has ended, and shared by all of them. */
	private final class Heartbeat {

		private Boolean up;

		boolean up() {
			if (up == null) {
				HealthCheckResult result = probe.check(settings.healthUrl());
				up = result.outcome() == HealthCheckResult.Outcome.HEALTHY;
				if (!up) {
					log.info("{} is DOWN: {}", settings.healthUrl(), result.detail());
				}
			}
			return up;
		}

	}

	/**
	 * Runs a deal's heartbeat when a new round has ended since the last one: probes the provider and records the result
	 * as that round's observation. Rounds missed while the monitor was down aren't backfilled; the program counts them
	 * as down.
	 */
	private void beat(UptimeDeal deal, ChainDeal account, Instant now, Heartbeat heartbeat) {
		int round = account.roundEndedBy(now);
		Integer last = lastHeartbeat.get(deal.address());
		if (round < 0 || account.isRecorded(round) || (last != null && round <= last)) {
			return;
		}
		lastHeartbeat.put(deal.address(), round);
		boolean up = heartbeat.up();
		try {
			chain.recordObservation(deal.address(), account, round, up);
			log.info("Recorded deal {} round {} as {}", deal.address(), round, up ? "UP" : "DOWN");
		}
		catch (RuntimeException e) {
			log.warn("Could not report {} observation for deal {} round {}: {}", up ? "UP" : "DOWN", deal.address(),
					round, e.getMessage());
			pendingObservations.add(new PendingObservation(deal.address(), round, up));
		}
	}

	/**
	 * Resends observations whose first send failed, until one lands, the round is recorded anyway, or the deal stops
	 * accepting observations (closed, or past its window and the program's grace).
	 */
	private void retryObservations(Instant now) {
		for (PendingObservation observation : List.copyOf(pendingObservations)) {
			try {
				ChainDeal account = chain.readDeal(observation.address());
				if (account == null || !account.accepted() || account.isRecorded(observation.round())
						|| !now.isBefore(account.startsAt().plusSeconds(account.durationSeconds() + 10))) {
					pendingObservations.remove(observation);
					continue;
				}
				chain.recordObservation(observation.address(), account, observation.round(), observation.up());
				pendingObservations.remove(observation);
				log.info("Recorded deal {} round {} as {} on retry", observation.address(), observation.round(),
						observation.up() ? "UP" : "DOWN");
			}
			catch (RuntimeException e) {
				log.warn("Retrying the observation of deal {} round {} failed: {}", observation.address(),
						observation.round(), e.getMessage());
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
