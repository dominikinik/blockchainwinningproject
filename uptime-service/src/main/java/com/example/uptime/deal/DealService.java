package com.example.uptime.deal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * service's key as its oracle, then registers it here with a window length. The window starts at the
 * next whole second. Once it has ended (plus a grace period for the last second to be flushed), the
 * service counts this service's own recorded up seconds in the window and sends {@code settle_deal}.
 * The program decides who gets the escrow; the service reads that verdict back from the
 * {@code DealSettled} event.
 * <p>
 * Tracked deals live in memory only: after a restart, deals registered before it are no longer
 * settled, and their escrow stays locked.
 */
@Service
public class DealService {

	private static final Logger log = LoggerFactory.getLogger(DealService.class);

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
	 * @param address         Base58 address of the {@code Deal} account, already created on chain
	 * @param durationSeconds length of the uptime window, 1 to {@code deal.max-duration-seconds}
	 * @return the tracked deal, {@code ACTIVE}, whose window starts at the next whole second
	 * @throws IllegalArgumentException if the duration is out of range, the address is invalid, or the
	 *                                  account is missing, isn't a deal of this program, or names
	 *                                  another oracle
	 * @throws IllegalStateException    if the deal is already registered
	 * @throws SolanaRpc.SolanaRpcException if the RPC node can't be read
	 */
	public TrackedDeal register(String address, long durationSeconds) {
		if (durationSeconds < 1 || durationSeconds > properties.maxDurationSeconds()) {
			throw new IllegalArgumentException(
					"durationSeconds must be between 1 and " + properties.maxDurationSeconds());
		}
		if (address == null || address.isBlank()) {
			throw new IllegalArgumentException("address is required");
		}
		Base58.decodePublicKey(address);
		if (deals.containsKey(address)) {
			throw new IllegalStateException("Deal " + address + " is already registered");
		}
		DealAccount deal = readDeal(address);
		if (deal == null) {
			throw new IllegalArgumentException("No uptime_deal account exists at " + address);
		}
		if (!deal.oracle().equals(oracle.address())) {
			throw new IllegalArgumentException(
					"Deal names oracle " + deal.oracle() + ", but this service is " + oracle.address());
		}
		ensureOracleFunded();

		Instant start = clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
		TrackedDeal tracked = new TrackedDeal(address, deal.payer(), deal.recipient(), deal.amountLamports(),
				durationSeconds, start, start.plusSeconds(durationSeconds), Status.ACTIVE, null, null, null, null,
				null, 0, null);
		if (deals.putIfAbsent(address, tracked) != null) {
			throw new IllegalStateException("Deal " + address + " is already registered");
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
			// A settlement sent earlier may have landed after its confirmation timed out.
			return deal.upSeconds() != null ? deal.settled(null)
					: deal.failed("The deal account no longer exists; it was settled elsewhere");
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
		Boolean paid = logs == null ? null : DealProgram.paidToRecipient(logs);
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
