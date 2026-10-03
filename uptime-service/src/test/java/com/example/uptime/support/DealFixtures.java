package com.example.uptime.support;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

import com.example.uptime.solana.Base58;
import com.example.uptime.solana.OracleKey;

/** Builds {@code uptime_deal} account data and event logs the way the program writes them. */
public final class DealFixtures {

	public static final String PROGRAM_ID = "EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r";

	private static final byte[] DEAL = { 125, (byte) 223, (byte) 160, (byte) 234, 71, (byte) 162, (byte) 182,
			(byte) 219 };

	private static final byte[] DEAL_SETTLED = { 41, (byte) 213, (byte) 235, 64, 55, (byte) 168, 51, 76 };

	private static final byte[] DEAL_CANCELLED = { (byte) 229, (byte) 189, 86, (byte) 176, (byte) 134, (byte) 151, 43,
			(byte) 152 };

	private DealFixtures() {
	}

	/** A fresh random Base58 address. */
	public static String newAddress() {
		return OracleKey.generate().address();
	}

	/**
	 * Starts a {@code Deal} account: active, 10 one-second rounds from {@code startsAt}, 90% required, no
	 * guarantee, nothing recorded.
	 */
	public static Deal deal(String payer, String recipient, String oracle, long startsAt) {
		return new Deal(payer, recipient, oracle, startsAt);
	}

	/** A {@code Deal} account under construction; every setter returns {@code this}. */
	public static final class Deal {

		private final String payer;

		private final String recipient;

		private final String oracle;

		private long dealId = 1;

		private long amount = 500_000_000L;

		private long stake;

		private boolean active = true;

		private long startsAt;

		private long duration = 10;

		private long interval = 1;

		private int minBps = 9_000;

		private int up;

		private int down;

		private byte[] recorded;

		private Deal(String payer, String recipient, String oracle, long startsAt) {
			this.payer = payer;
			this.recipient = recipient;
			this.oracle = oracle;
			this.startsAt = startsAt;
		}

		public Deal dealId(long dealId) {
			this.dealId = dealId;
			return this;
		}

		public Deal amount(long amount) {
			this.amount = amount;
			return this;
		}

		/** Sets a guarantee and makes the deal await the provider (starts_at 0). */
		public Deal awaitingProvider(long stake) {
			this.stake = stake;
			this.active = false;
			this.startsAt = 0;
			return this;
		}

		public Deal window(long duration, long interval) {
			this.duration = duration;
			this.interval = interval;
			return this;
		}

		public Deal minBps(int minBps) {
			this.minBps = minBps;
			return this;
		}

		/** Marks rounds as recorded on chain, with their UP/DOWN result. */
		public Deal recorded(int[] rounds, boolean upResult) {
			byte[] bits = bitmap();
			for (int round : rounds) {
				bits[round / 8] |= (byte) (1 << (round % 8));
				if (upResult) {
					up++;
				}
				else {
					down++;
				}
			}
			recorded = bits;
			return this;
		}

		private byte[] bitmap() {
			if (recorded == null) {
				recorded = new byte[(int) ((duration / interval + 7) / 8)];
			}
			return recorded;
		}

		/** The raw account data, laid out like the program's {@code Deal}. */
		public byte[] data() {
			byte[] bits = bitmap();
			return ByteBuffer.allocate(8 + 96 + 24 + 1 + 24 + 2 + 12 + 1 + 4 + bits.length)
				.order(ByteOrder.LITTLE_ENDIAN)
				.put(DEAL)
				.put(Base58.decodePublicKey(payer))
				.put(Base58.decodePublicKey(recipient))
				.put(Base58.decodePublicKey(oracle))
				.putLong(dealId)
				.putLong(amount)
				.putLong(stake)
				.put((byte) (active ? 1 : 0))
				.putLong(startsAt)
				.putLong(duration)
				.putLong(interval)
				.putShort((short) minBps)
				.putInt((int) (duration / interval))
				.putInt(up)
				.putInt(down)
				.put((byte) 254)
				.putInt(bits.length)
				.put(bits)
				.array();
		}

	}

	/** The {@code Program data:} log line of a {@code DealCancelled} event. */
	public static String dealCancelledLog(String deal, String payer, long amount) {
		byte[] event = ByteBuffer.allocate(8 + 32 + 32 + 8)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(DEAL_CANCELLED)
			.put(Base58.decodePublicKey(deal))
			.put(Base58.decodePublicKey(payer))
			.putLong(amount)
			.array();
		return "Program data: " + Base64.getEncoder().encodeToString(event);
	}

	/** The {@code Program data:} log line of a {@code DealSettled} event. */
	public static String dealSettledLog(String deal, int up, int down, int total, boolean paid, long payout) {
		byte[] event = ByteBuffer.allocate(8 + 32 + 4 * 3 + 2 + 1 + 8)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(DEAL_SETTLED)
			.put(Base58.decodePublicKey(deal))
			.putInt(up)
			.putInt(down)
			.putInt(total)
			.putShort((short) 9_000)
			.put((byte) (paid ? 1 : 0))
			.putLong(payout)
			.array();
		return "Program data: " + Base64.getEncoder().encodeToString(event);
	}

}
