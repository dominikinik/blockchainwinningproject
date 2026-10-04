package com.example.monitor.infrastructure.solana;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import com.example.monitor.infrastructure.solana.SolanaTransaction.AccountMeta;
import com.example.monitor.infrastructure.solana.SolanaTransaction.Instruction;

/**
 * Binary layout of the {@code uptime_deal} Anchor program (see {@code uptime-deal/}): the {@code Deal}
 * account, the {@code record_observation} and {@code settle_deal} instructions, and the
 * {@code DealSettled} and {@code DealCancelled} events. The discriminators are the first 8 bytes of
 * {@code sha256("account:Deal")}, {@code sha256("global:<instruction>")} and
 * {@code sha256("event:<Event>")}, as listed in the program's IDL.
 */
public final class DealProgram {

	static final byte[] DEAL_DISCRIMINATOR = { 125, (byte) 223, (byte) 160, (byte) 234, 71, (byte) 162, (byte) 182,
			(byte) 219 };

	static final byte[] RECORD_OBSERVATION_DISCRIMINATOR = { 37, (byte) 148, 41, (byte) 216, 83, 104, (byte) 162,
			96 };

	static final byte[] SETTLE_DEAL_DISCRIMINATOR = { 28, 10, (byte) 168, (byte) 174, (byte) 203, (byte) 149,
			(byte) 134, 54 };

	static final byte[] DEAL_SETTLED_DISCRIMINATOR = { 41, (byte) 213, (byte) 235, 64, 55, (byte) 168, 51, 76 };

	static final byte[] DEAL_CANCELLED_DISCRIMINATOR = { (byte) 229, (byte) 189, 86, (byte) 176, (byte) 134,
			(byte) 151, 43, (byte) 152 };

	/**
	 * How long after a window ends the program still accepts observations; settlement opens exactly then
	 * (the program's {@code OBSERVATION_GRACE_SECONDS}).
	 */
	public static final long OBSERVATION_GRACE_SECONDS = 10;

	/** Byte offset of {@code oracle} in a {@code Deal}: discriminator, payer, recipient. */
	public static final int ORACLE_OFFSET = 8 + 32 + 32;

	/**
	 * Fixed part of a {@code Deal}: discriminator, payer, recipient, oracle, deal_id, amount_lamports,
	 * provider_stake_lamports, status, starts_at, duration_seconds, check_interval_seconds, min_uptime_bps,
	 * total_rounds, up_checks, down_checks, bump, and the bitmap's length prefix.
	 */
	private static final int DEAL_FIXED_SIZE = 8 + 32 * 3 + 8 * 4 + 1 + 8 * 3 + 2 + 4 * 3 + 1 + 4;

	private static final String EVENT_LOG_PREFIX = "Program data: ";

	private DealProgram() {
	}

	/**
	 * A decoded {@code Deal} account: the terms and the authoritative on-chain SLA counters.
	 *
	 * @param payer                 Base58 customer wallet; funded the payment and wins it all on a breach
	 * @param recipient             Base58 provider wallet; wins it all when the SLA is met
	 * @param oracle                Base58 key allowed to record observations
	 * @param dealId                the payer-chosen id
	 * @param amountLamports        the customer payment
	 * @param providerStakeLamports the provider guarantee (0 for none)
	 * @param acceptDeadline        chain time after which a proposal expires
	 * @param active                {@code false} while the deal waits for the provider's guarantee
	 * @param startsAt              chain time at which the window started; {@code null} until active
	 * @param durationSeconds       length of the window in seconds
	 * @param checkIntervalSeconds  length of one monitoring round in seconds
	 * @param minUptimeBps          required uptime in basis points
	 * @param totalRounds           rounds in the window; unobserved ones count as down
	 * @param upChecks              rounds recorded UP
	 * @param downChecks            rounds recorded DOWN
	 * @param recorded              one bit per round, set once that round is recorded
	 */
	public record DealAccount(String payer, String recipient, String oracle, long dealId, long amountLamports,
			long providerStakeLamports, Instant acceptDeadline, boolean active, Instant startsAt, long durationSeconds,
			long checkIntervalSeconds, int minUptimeBps, int totalRounds, int upChecks, int downChecks,
			byte[] recorded) {

		/**
		 * Tells whether a round is already counted on chain.
		 *
		 * @param round the zero-based round
		 * @return {@code true} if its bit is set; {@code false} if not, or if it lies beyond the bitmap
		 */
		public boolean isRecorded(int round) {
			return round >= 0 && round / 8 < recorded.length && (recorded[round / 8] & (1 << (round % 8))) != 0;
		}

		/**
		 * @return the end of the window (exclusive), or {@code null} until the deal is active
		 */
		public Instant endsAt() {
			return startsAt == null ? null : startsAt.plusSeconds(durationSeconds);
		}

		/**
		 * @return when observations close and settlement opens on chain, or {@code null} until active
		 */
		public Instant settleOpensAt() {
			return startsAt == null ? null : endsAt().plusSeconds(OBSERVATION_GRACE_SECONDS);
		}

	}

	/**
	 * How a deal account was closed.
	 *
	 * @param cancelled       {@code true} if the payer cancelled it ({@code DealCancelled}), {@code false} if
	 *                        it was settled ({@code DealSettled})
	 * @param paidToRecipient for a settlement, whether the provider won; {@code null} if cancelled
	 * @param upChecks        for a settlement, the on-chain UP rounds; {@code null} if cancelled
	 * @param downChecks      for a settlement, the on-chain DOWN rounds; {@code null} if cancelled
	 * @param totalRounds     for a settlement, all rounds of the window; {@code null} if cancelled
	 */
	public record Outcome(boolean cancelled, Boolean paidToRecipient, Integer upChecks, Integer downChecks,
			Integer totalRounds) {
	}

	/**
	 * Decodes {@code Deal} account data.
	 *
	 * @param data the raw account data
	 * @return the decoded deal
	 * @throws IllegalArgumentException if the data is too short or isn't a {@code Deal}
	 */
	public static DealAccount decodeDeal(byte[] data) {
		if (data.length < DEAL_FIXED_SIZE || !Arrays.equals(data, 0, 8, DEAL_DISCRIMINATOR, 0, 8)) {
			throw new IllegalArgumentException("Account is not an uptime_deal Deal");
		}
		ByteBuffer buf = ByteBuffer.wrap(data, 8, data.length - 8).order(ByteOrder.LITTLE_ENDIAN);
		String payer = readKey(buf);
		String recipient = readKey(buf);
		String oracle = readKey(buf);
		long dealId = buf.getLong();
		long amount = buf.getLong();
		long stake = buf.getLong();
		Instant acceptDeadline = Instant.ofEpochSecond(buf.getLong());
		boolean active = buf.get() == 1;
		long startsAt = buf.getLong();
		long duration = buf.getLong();
		long interval = buf.getLong();
		int minBps = Short.toUnsignedInt(buf.getShort());
		int totalRounds = buf.getInt();
		int up = buf.getInt();
		int down = buf.getInt();
		buf.get(); // bump
		int bitmapLength = buf.getInt();
		if (bitmapLength < 0 || bitmapLength > buf.remaining()) {
			throw new IllegalArgumentException("Deal bitmap is truncated");
		}
		byte[] recorded = new byte[bitmapLength];
		buf.get(recorded);
		return new DealAccount(payer, recipient, oracle, dealId, amount, stake, acceptDeadline, active,
				active ? Instant.ofEpochSecond(startsAt) : null, duration, interval, minBps, totalRounds, up, down,
				recorded);
	}

	/**
	 * Builds the {@code record_observation} instruction: the oracle's UP/DOWN report of one round.
	 *
	 * @param programId   Base58 program id
	 * @param oracle      Base58 oracle, which signs
	 * @param dealAddress Base58 deal address
	 * @param round       the zero-based round
	 * @param up          whether the service was up for the whole round
	 * @return the instruction, with accounts in the program's order: oracle, deal
	 */
	public static Instruction observationInstruction(String programId, String oracle, String dealAddress, int round,
			boolean up) {
		byte[] data = ByteBuffer.allocate(8 + 4 + 1)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(RECORD_OBSERVATION_DISCRIMINATOR)
			.putInt(round)
			.put((byte) (up ? 1 : 0))
			.array();
		List<AccountMeta> accounts = List.of(new AccountMeta(Base58.decodePublicKey(oracle), true, false),
				new AccountMeta(Base58.decodePublicKey(dealAddress), false, true));
		return new Instruction(Base58.decodePublicKey(programId), accounts, data);
	}

	/**
	 * Builds the {@code settle_deal} instruction. It carries no data besides its discriminator: the
	 * program judges the SLA from the deal's own counters, so the caller has nothing to report.
	 *
	 * @param programId   Base58 program id
	 * @param caller      Base58 signer that pays the fee; anyone may settle
	 * @param dealAddress Base58 deal address
	 * @param deal        the decoded deal, for its payer and recipient
	 * @return the instruction, with accounts in the program's order: caller, deal, payer, recipient
	 */
	public static Instruction settleInstruction(String programId, String caller, String dealAddress,
			DealAccount deal) {
		List<AccountMeta> accounts = List.of(new AccountMeta(Base58.decodePublicKey(caller), true, false),
				new AccountMeta(Base58.decodePublicKey(dealAddress), false, true),
				new AccountMeta(Base58.decodePublicKey(deal.payer()), false, true),
				new AccountMeta(Base58.decodePublicKey(deal.recipient()), false, true));
		return new Instruction(Base58.decodePublicKey(programId), accounts, SETTLE_DEAL_DISCRIMINATOR.clone());
	}

	/**
	 * Finds the {@code DealSettled} event in transaction logs and reads who got the escrow.
	 *
	 * @param logs the transaction's log lines
	 * @return {@code true} if the provider won, {@code false} if the customer did, or {@code null} if the
	 *         logs hold no {@code DealSettled} event
	 */
	public static Boolean paidToRecipient(List<String> logs) {
		Outcome outcome = closedBy(logs, null);
		return outcome == null || outcome.cancelled() ? null : outcome.paidToRecipient();
	}

	/**
	 * Finds the {@code DealSettled} or {@code DealCancelled} event of one deal in transaction logs.
	 *
	 * @param logs        the transaction's log lines
	 * @param dealAddress Base58 deal address the event must name; {@code null} accepts any deal
	 * @return how the deal was closed, or {@code null} if the logs hold no such event for that deal
	 */
	public static Outcome closedBy(List<String> logs, String dealAddress) {
		byte[] deal = dealAddress == null ? null : Base58.decodePublicKey(dealAddress);
		for (String line : logs) {
			if (!line.startsWith(EVENT_LOG_PREFIX)) {
				continue;
			}
			byte[] event;
			try {
				event = Base64.getDecoder().decode(line.substring(EVENT_LOG_PREFIX.length()).strip());
			}
			catch (IllegalArgumentException e) {
				continue;
			}
			if (event.length < 8 + 32 || (deal != null && !Arrays.equals(event, 8, 8 + 32, deal, 0, 32))) {
				continue;
			}
			ByteBuffer buf = ByteBuffer.wrap(event, 8 + 32, event.length - 8 - 32).order(ByteOrder.LITTLE_ENDIAN);
			// Discriminator, deal, up_checks, down_checks, total_rounds, min_uptime_bps, paid_to_recipient,
			// payout_lamports.
			if (event.length >= 8 + 32 + 4 * 3 + 2 + 1 && Arrays.equals(event, 0, 8, DEAL_SETTLED_DISCRIMINATOR, 0, 8)) {
				int up = buf.getInt();
				int down = buf.getInt();
				int total = buf.getInt();
				buf.getShort();
				return new Outcome(false, buf.get() != 0, up, down, total);
			}
			// Discriminator, deal, payer, amount_lamports.
			if (event.length >= 8 + 32 + 32 + 8 && Arrays.equals(event, 0, 8, DEAL_CANCELLED_DISCRIMINATOR, 0, 8)) {
				return new Outcome(true, null, null, null, null);
			}
		}
		return null;
	}

	private static String readKey(ByteBuffer buf) {
		byte[] key = new byte[32];
		buf.get(key);
		return Base58.encode(key);
	}

}
