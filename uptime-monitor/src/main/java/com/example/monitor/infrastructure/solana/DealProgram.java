package com.example.monitor.infrastructure.solana;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.example.monitor.infrastructure.solana.SolanaTransaction.AccountMeta;
import com.example.monitor.infrastructure.solana.SolanaTransaction.Instruction;

/**
 * Binary layout of the {@code uptime_deal} Anchor program (see {@code uptime-deal/}) that the proxy needs: the
 * {@code Deal} account and the {@code record_observation} instruction. The discriminators are the first 8 bytes of
 * {@code sha256("account:Deal")} and {@code sha256("global:record_observation")}, as listed in the program's IDL.
 */
public final class DealProgram {

	static final byte[] DEAL_DISCRIMINATOR = { 125, (byte) 223, (byte) 160, (byte) 234, 71, (byte) 162, (byte) 182,
			(byte) 219 };

	static final byte[] RECORD_OBSERVATION_DISCRIMINATOR = { 37, (byte) 148, 41, (byte) 216, 83, 104, (byte) 162,
			96 };

	/** Byte offset of {@code oracle} in a {@code Deal}: discriminator, payer, recipient. */
	public static final int ORACLE_OFFSET = 8 + 32 + 32;

	/**
	 * Fixed part of a {@code Deal}: discriminator, payer, recipient, oracle, deal_id, amount_lamports,
	 * provider_stake_lamports, status, starts_at, duration_seconds, check_interval_seconds, min_uptime_bps,
	 * total_rounds, up_checks, down_checks, bump, and the bitmap's length prefix.
	 */
	private static final int DEAL_FIXED_SIZE = 8 + 32 * 3 + 8 * 4 + 1 + 8 * 3 + 2 + 4 * 3 + 1 + 4;

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

	private static String readKey(ByteBuffer buf) {
		byte[] key = new byte[32];
		buf.get(key);
		return Base58.encode(key);
	}

}
