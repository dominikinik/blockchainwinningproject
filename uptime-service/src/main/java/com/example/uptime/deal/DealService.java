package com.example.uptime.deal;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.uptime.deal.DealProgram.DealAccount;
import com.example.uptime.deal.TrackedDeal.Status;
import com.example.uptime.solana.Base58;
import com.example.uptime.solana.OracleKey;
import com.example.uptime.solana.SolanaRpc;
import com.example.uptime.solana.SolanaRpc.AccountInfo;
import com.example.uptime.solana.SolanaRpc.SignatureStatus;
import com.example.uptime.solana.SolanaTransaction;
import com.example.uptime.uptime.UptimePoint;
import com.example.uptime.uptime.UptimeQueryService;

/**
 * Acts as the oracle of the {@code uptime_deal} program. A wallet creates a deal on chain naming this
 * service's key as its oracle, then registers it here. The window comes from the deal account itself
 * ({@code starts_at} and {@code duration_seconds}, set by the chain at creation), never from the caller.
 * Once it has ended (plus a grace period for the last second to be flushed), the
 * service counts this service's own recorded up seconds in the window and sends {@code settle_deal}.
 * The program decides who gets the escrow; the service reads that verdict back from the
 * {@code DealSettled} event.
 * <p>
 * Tracked deals live in memory only: after a restart, deals registered before it are no longer
 * settled, and their escrow stays in the deal until the payer cancels it.
 */
@Service
public class DealService {

	private static final Logger log = LoggerFactory.getLogger(DealService.class);

	/** How many recent transactions of a vanished deal account are searched for its closing event. */
	private static final int HISTORY_LIMIT = 10;

	private final SolanaRpc rpc;

	private final OracleKey oracle;

	private final UptimeQueryService uptime;

	private final DealProperties properties;

	private final Clock clock;

	private final Map<String, TrackedDeal> deals = new ConcurrentHashMap<>();

	public DealService(SolanaRpc rpc, OracleKey oracle, UptimeQueryService uptime, DealProperties properties,
			Clock clock) {
		this.rpc = rpc;
		this.oracle = oracle;
		this.uptime = uptime;
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
	 * Starts watching an on-chain deal.
	 *
	 * @param address Base58 address of the {@code Deal} account, already created on chain
	 * @return the tracked deal, {@code ACTIVE}, whose window is {@code [starts_at, starts_at +
	 *         duration_seconds)} from the account; if that window is already over it settles on the next
	 *         tick
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
		if (!deal.oracle().equals(oracle.address())) {
			throw new IllegalArgumentException(
					"Deal names oracle " + deal.oracle() + ", but this service is " + oracle.address());
		}
		if (deal.durationSeconds() < 1 || deal.durationSeconds() > properties.maxDurationSeconds()) {
			throw new IllegalArgumentException("Deal's on-chain duration of " + deal.durationSeconds()
					+ " seconds must be between 1 and " + properties.maxDurationSeconds());
		}
		ensureOracleFunded();

		Instant start = deal.startsAt();
		TrackedDeal tracked = new TrackedDeal(address, deal.payer(), deal.recipient(), deal.amountLamports(),
				deal.durationSeconds(), start, start.plusSeconds(deal.durationSeconds()), Status.ACTIVE, null, null,
				null, null, null, 0, null);
		if (deals.putIfAbsent(address, tracked) != null) {
			throw new DealAlreadyRegisteredException(address);
		}
		log.info("Watching deal {} from {} to {}", address, tracked.startsAt(), tracked.endsAt());
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
	 * @return every tracked deal, newest window first
	 */
	public List<TrackedDeal> list() {
		return deals.values().stream().sorted(Comparator.comparing(TrackedDeal::startsAt).reversed()).toList();
	}

	/**
	 * Advances every active deal whose window is over: sends its settlement, or checks on a sent one.
	 * Runs every {@code deal.poll-interval-ms}; one deal's failure never stops the others.
	 */
	@Scheduled(fixedDelayString = "${deal.poll-interval-ms}")
	public void settleDue() {
		Instant now = clock.instant();
		for (TrackedDeal deal : deals.values()) {
			if (deal.status() != Status.ACTIVE
					|| now.isBefore(deal.endsAt().plusSeconds(properties.settleGraceSeconds()))) {
				continue;
			}
			TrackedDeal next;
			try {
				next = deal.signature() == null ? send(deal, now) : checkSent(deal, now);
			}
			catch (RuntimeException e) {
				log.warn("Settling deal {} failed: {}", deal.address(), e.getMessage());
				next = deal.failedAttempt(e.getMessage(), properties.maxSettleAttempts());
			}
			deals.put(deal.address(), next);
		}
	}

	/** Measures the window and sends {@code settle_deal}. */
	private TrackedDeal send(TrackedDeal deal, Instant now) {
		DealAccount account = readDeal(deal.address());
		if (account == null) {
			return closedOnChain(deal);
		}
		List<UptimePoint> points = uptime.range(deal.startsAt(), deal.endsAt().minusSeconds(1));
		long up = points.stream().filter(p -> !p.down()).count();
		long total = points.size();
		byte[] tx = SolanaTransaction.signed(oracle,
				List.of(DealProgram.settleInstruction(properties.programId(), oracle.address(), deal.address(),
						account, up, total)),
				rpc.getLatestBlockhash());
		String signature = rpc.sendTransaction(tx);
		log.info("Settling deal {} with {}/{} up seconds: {}", deal.address(), up, total, signature);
		return deal.sent(up, total, signature, now);
	}

	/**
	 * The deal account is gone: find out from the address's recent transactions whether it was settled
	 * (for instance by a send that threw after the node accepted it) or cancelled by the payer.
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
			return deal.settledBy(signature, outcome.paidToRecipient(), outcome.upSeconds(), outcome.totalSeconds());
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
		Boolean paid = outcome == null || outcome.cancelled() ? null : outcome.paidToRecipient();
		log.info("Deal {} settled, paid to recipient: {}", deal.address(), paid);
		return deal.settled(paid);
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

	/** Tops up the oracle from the faucet when it can't pay settlement fees (localnet/devnet only). */
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
