package com.example.uptime.deal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.uptime.deal.DealProgram.DealAccount;
import com.example.uptime.solana.Base58;
import com.example.uptime.solana.OracleKey;
import com.example.uptime.solana.SolanaRpc;
import com.example.uptime.solana.SolanaRpc.AccountInfo;
import com.example.uptime.solana.SolanaRpc.ProgramAccount;
import com.example.uptime.solana.SolanaRpc.SignatureStatus;
import com.example.uptime.solana.SolanaTransaction;
import com.example.uptime.state.ApplicationStateHealthIndicator;

/**
 * The monitor (oracle) of the {@code uptime_deal} program. It observes; the program decides.
 * <ul>
 * <li>{@link #observe()} samples this service's health and records, per deal and per round, whether every
 * sample in the round was UP.</li>
 * <li>{@link #advance()} sends {@code record_observation} for each finished round the chain hasn't recorded
 * yet. Once the window and the program's observation grace are over, it calls {@code settle_deal}, which
 * takes no figures: the program judges the SLA from its own counters and pays the winner. The service
 * only reads the verdict back from the {@code DealSettled} event.</li>
 * <li>{@link #discover()} finds open deals naming this oracle, so a restart resumes monitoring them.</li>
 * </ul>
 * Nothing here reads the uptime database: deleting it changes neither the counters nor the verdict.
 * Rounds this service didn't witness (it was down, or not yet tracking the deal) are never reported and
 * count as down on chain.
 */
@Service
public class DealService {

	private static final Logger log = LoggerFactory.getLogger(DealService.class);

	/** How many recent transactions of a vanished deal account are searched for its closing event. */
	private static final int HISTORY_LIMIT = 10;

	private final SolanaRpc rpc;

	private final OracleKey oracle;

	private final ApplicationStateHealthIndicator health;

	private final DealProperties properties;

	private final Clock clock;

	private final Map<String, TrackedDeal> deals = new ConcurrentHashMap<>();

	private final Map<String, RoundLog> rounds = new ConcurrentHashMap<>();

	public DealService(SolanaRpc rpc, OracleKey oracle, ApplicationStateHealthIndicator health,
			DealProperties properties, Clock clock) {
		this.rpc = rpc;
		this.oracle = oracle;
		this.health = health;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Returns the oracle's address.
	 *
	 * @return the Base58 key that deals must name as their oracle
	 */
	public String oracleAddress() {
		return oracle.address();
	}

	/**
	 * Starts monitoring an on-chain deal.
	 *
	 * @param address Base58 address of the {@code Deal} account, already created on chain
	 * @return the tracked deal, {@code ACTIVE} or {@code AWAITING_PROVIDER}; its window, interval and
	 *         threshold come from the account
	 * @throws IllegalArgumentException if the address is invalid, the account is missing, isn't a deal of
	 *                                  this program, names another oracle, or has a duration of 0 or
	 *                                  above {@code deal.max-duration-seconds}
	 * @throws DealAlreadyRegisteredException if the deal is already registered
	 * @throws SolanaRpc.SolanaRpcException if the RPC node can't be read
	 */
	public TrackedDeal register(String address) {
		if (address == null || address.isBlank()) {
			throw new IllegalArgumentException("address is required");
		}
		Base58.decodePublicKey(address);
		if (deals.containsKey(address)) {
			throw new DealAlreadyRegisteredException(address);
		}
		DealAccount deal = readDeal(address);
		if (deal == null) {
			throw new IllegalArgumentException("No uptime_deal account exists at " + address);
		}
		ensureOracleFunded();
		TrackedDeal tracked = track(address, deal);
		if (tracked == null) {
			throw new DealAlreadyRegisteredException(address);
		}
		return tracked;
	}

	/**
	 * Looks up a tracked deal.
	 *
	 * @param address Base58 deal address
	 * @return the tracked deal
	 * @throws NoSuchElementException if the deal isn't registered here
	 */
	public TrackedDeal get(String address) {
		TrackedDeal deal = deals.get(address);
		if (deal == null) {
			throw new NoSuchElementException("Deal " + address + " is not registered");
		}
		return deal;
	}

	/**
	 * Lists tracked deals.
	 *
	 * @return every tracked deal, newest window first (deals awaiting the provider first)
	 */
	public List<TrackedDeal> list() {
		return deals.values()
			.stream()
			.sorted(Comparator.comparing(TrackedDeal::startsAt, Comparator.nullsFirst(Comparator.reverseOrder())))
			.toList();
	}

	/**
	 * Finds open deals that name this oracle and starts monitoring the ones not tracked yet. Runs at
	 * startup and every {@code deal.discover-interval-ms}; a failure is logged and retried next time.
	 */
	@Scheduled(fixedDelayString = "${deal.discover-interval-ms}")
	public void discover() {
		List<ProgramAccount> accounts;
		try {
			accounts = rpc.getProgramAccounts(properties.programId(), DealProgram.ORACLE_OFFSET, oracle.address());
		}
		catch (RuntimeException e) {
			log.warn("Discovering deals failed: {}", e.getMessage());
			return;
		}
		for (ProgramAccount account : accounts) {
			if (deals.containsKey(account.address())) {
				continue;
			}
			try {
				if (track(account.address(), DealProgram.decodeDeal(account.account().data())) != null) {
					log.info("Discovered deal {}", account.address());
					ensureOracleFunded();
				}
			}
			catch (IllegalArgumentException e) {
				log.debug("Skipping account {}: {}", account.address(), e.getMessage());
			}
		}
	}

	/**
	 * Samples this service's health once and adds it to the current round of every running deal. Runs
	 * every {@code deal.observe-interval-ms}, which must be well below a deal's check interval. Makes no
	 * RPC calls.
	 */
	@Scheduled(fixedRateString = "${deal.observe-interval-ms}")
	public void observe() {
		boolean up = Status.UP.equals(health.health().getStatus());
		Instant now = clock.instant();
		for (TrackedDeal deal : deals.values()) {
			if (deal.status() != TrackedDeal.Status.ACTIVE || deal.startsAt() == null
					|| now.isBefore(deal.startsAt()) || !now.isBefore(deal.endsAt())) {
				continue;
			}
			long round = Duration.between(deal.startsAt(), now).toMillis() / (deal.checkIntervalSeconds() * 1000);
			RoundLog roundLog = rounds.get(deal.address());
			if (roundLog != null) {
				roundLog.witness((int) round, up);
			}
		}
	}

	/**
	 * Moves every open deal forward: refreshes it from chain, reports finished rounds, and settles it once
	 * the program allows. Runs every {@code deal.poll-interval-ms}; one deal's failure never stops the
	 * others.
	 */
	@Scheduled(fixedDelayString = "${deal.poll-interval-ms}")
	public void advance() {
		Instant now = clock.instant();
		for (TrackedDeal deal : deals.values()) {
			if (deal.status() != TrackedDeal.Status.ACTIVE && deal.status() != TrackedDeal.Status.AWAITING_PROVIDER) {
				continue;
			}
			TrackedDeal next;
			try {
				next = advance(deal, now);
			}
			catch (RuntimeException e) {
				log.warn("Advancing deal {} failed: {}", deal.address(), e.getMessage());
				next = settling(deal, now) ? deal.failedAttempt(e.getMessage(), properties.maxSettleAttempts()) : deal;
			}
			deals.put(deal.address(), next);
			if (next.status() != TrackedDeal.Status.ACTIVE && next.status() != TrackedDeal.Status.AWAITING_PROVIDER) {
				rounds.remove(deal.address());
			}
		}
	}

	/** Whether this service may try to settle: the program's window and grace, plus ours, are over. */
	private boolean settling(TrackedDeal deal, Instant now) {
		return deal.endsAt() != null && !now.isBefore(settleAt(deal.endsAt()));
	}

	private Instant settleAt(Instant endsAt) {
		return endsAt.plusSeconds(DealProgram.OBSERVATION_GRACE_SECONDS + properties.settleGraceSeconds());
	}

	private TrackedDeal advance(TrackedDeal deal, Instant now) {
		if (deal.signature() != null) {
			return checkSent(deal, now);
		}
		DealAccount account = readDeal(deal.address());
		if (account == null) {
			return closedOnChain(deal);
		}
		TrackedDeal next = deal.withChain(account);
		if (!account.active()) {
			return next;
		}
		if (now.isBefore(account.settleOpensAt())) {
			return report(next, account, now);
		}
		if (now.isBefore(settleAt(account.endsAt()))) {
			return next;
		}
		return settle(next, account, now);
	}

	/** Sends {@code record_observation} for every finished, witnessed round the chain hasn't recorded. */
	private TrackedDeal report(TrackedDeal deal, DealAccount account, Instant now) {
		RoundLog roundLog = rounds.get(deal.address());
		if (roundLog == null) {
			return deal;
		}
		Duration retry = Duration.ofMillis(properties.observationRetryMs());
		byte[] blockhash = null;
		int sent = 0;
		for (Map.Entry<Integer, Boolean> entry : roundLog.snapshot().entrySet()) {
			int round = entry.getKey();
			if (account.isRecorded(round)) {
				roundLog.forget(round);
				continue;
			}
			Instant roundEnd = account.startsAt().plusSeconds((round + 1L) * account.checkIntervalSeconds());
			if (now.isBefore(roundEnd) || !roundLog.due(round, now, retry)) {
				continue;
			}
			roundLog.markSent(round, now);
			try {
				if (blockhash == null) {
					blockhash = rpc.getLatestBlockhash();
				}
				byte[] tx = SolanaTransaction.signed(oracle, List.of(DealProgram.observationInstruction(
						properties.programId(), oracle.address(), deal.address(), round, entry.getValue())), blockhash);
				String signature = rpc.sendTransaction(tx);
				sent++;
				log.debug("Observed deal {} round {} {}: {}", deal.address(), round, entry.getValue() ? "UP" : "DOWN",
						signature);
			}
			catch (RuntimeException e) {
				log.warn("Reporting deal {} round {} failed, retrying: {}", deal.address(), round, e.getMessage());
			}
		}
		return sent == 0 ? deal : deal.observationsSent(sent);
	}

	/** Asks the program to settle. The instruction carries no figures; the program reads its own state. */
	private TrackedDeal settle(TrackedDeal deal, DealAccount account, Instant now) {
		byte[] tx = SolanaTransaction.signed(oracle,
				List.of(DealProgram.settleInstruction(properties.programId(), oracle.address(), deal.address(), account)),
				rpc.getLatestBlockhash());
		String signature = rpc.sendTransaction(tx);
		log.info("Settling deal {} (on chain: {} up, {} down of {} rounds): {}", deal.address(), account.upChecks(),
				account.downChecks(), account.totalRounds(), signature);
		return deal.sent(signature, now);
	}

	/**
	 * The deal account is gone: find out from the address's recent transactions whether it was settled
	 * (by anyone, or by a send of ours that threw) or cancelled by the payer.
	 */
	private TrackedDeal closedOnChain(TrackedDeal deal) {
		for (String signature : rpc.getSignaturesForAddress(deal.address(), HISTORY_LIMIT)) {
			List<String> logs = rpc.getTransactionLogs(signature);
			DealProgram.Outcome outcome = logs == null ? null : DealProgram.closedBy(logs, deal.address());
			if (outcome == null) {
				continue;
			}
			if (outcome.cancelled()) {
				log.info("Deal {} was cancelled by its payer: {}", deal.address(), signature);
				return deal.cancelled(signature);
			}
			log.info("Deal {} was settled by {}, paid to recipient: {}", deal.address(), signature,
					outcome.paidToRecipient());
			return deal.settledBy(signature, outcome);
		}
		return deal.failed("The deal account no longer exists and no DealSettled or DealCancelled event for it "
				+ "was found in its last " + HISTORY_LIMIT + " transactions");
	}

	/** Checks a sent settlement: done, failed, still pending, or lost and to be sent again. */
	private TrackedDeal checkSent(TrackedDeal deal, Instant now) {
		SignatureStatus status = rpc.getSignatureStatus(deal.signature());
		if (status == null || !status.confirmed()) {
			if (now.isAfter(deal.sentAt().plusSeconds(properties.confirmTimeoutSeconds()))) {
				return deal.failedAttempt("Settlement " + deal.signature() + " was not confirmed in time",
						properties.maxSettleAttempts());
			}
			return deal;
		}
		if (status.error() != null) {
			return deal.failed("Settlement transaction failed: " + status.error());
		}
		List<String> logs = rpc.getTransactionLogs(deal.signature());
		DealProgram.Outcome outcome = logs == null ? null : DealProgram.closedBy(logs, deal.address());
		log.info("Deal {} settled, paid to recipient: {}", deal.address(),
				outcome == null ? null : outcome.paidToRecipient());
		return deal.settled(outcome);
	}

	/**
	 * Validates a deal account and starts tracking it.
	 *
	 * @return the new tracked deal, or {@code null} if another thread tracked it first
	 * @throws IllegalArgumentException if the deal names another oracle or its duration is out of range
	 */
	private TrackedDeal track(String address, DealAccount deal) {
		if (!deal.oracle().equals(oracle.address())) {
			throw new IllegalArgumentException(
					"Deal names oracle " + deal.oracle() + ", but this service is " + oracle.address());
		}
		if (deal.durationSeconds() < 1 || deal.durationSeconds() > properties.maxDurationSeconds()) {
			throw new IllegalArgumentException("Deal's on-chain duration of " + deal.durationSeconds()
					+ " seconds must be between 1 and " + properties.maxDurationSeconds());
		}
		TrackedDeal tracked = TrackedDeal.of(address, deal);
		rounds.putIfAbsent(address, new RoundLog());
		if (deals.putIfAbsent(address, tracked) != null) {
			return null;
		}
		log.info("Monitoring deal {} ({}): {} rounds of {} s from {}", address, tracked.status(), tracked.totalRounds(),
				tracked.checkIntervalSeconds(), tracked.startsAt());
		return tracked;
	}

	private DealAccount readDeal(String address) {
		AccountInfo account = rpc.getAccountInfo(address);
		if (account == null) {
			return null;
		}
		if (!properties.programId().equals(account.owner())) {
			throw new IllegalArgumentException("Account " + address + " is not owned by the uptime_deal program");
		}
		return DealProgram.decodeDeal(account.data());
	}

	/** Tops up the oracle from the faucet when it can't pay fees (localnet/devnet only). */
	private void ensureOracleFunded() {
		if (properties.oracleMinLamports() <= 0) {
			return;
		}
		try {
			if (rpc.getBalance(oracle.address()) < properties.oracleMinLamports()) {
				rpc.requestAirdrop(oracle.address(), properties.oracleAirdropLamports());
				log.info("Requested an airdrop for oracle {}", oracle.address());
			}
		}
		catch (SolanaRpc.SolanaRpcException e) {
			log.warn("Could not fund oracle {}: {}", oracle.address(), e.getMessage());
		}
	}

}
