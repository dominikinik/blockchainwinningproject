package com.example.monitor.support;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.example.monitor.infrastructure.solana.Base58;
import com.example.monitor.infrastructure.solana.OracleKey;

/** Builds {@code uptime_deal} account data the way the program writes them. */
public final class DealFixtures {

	public static final String PROGRAM_ID = "EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r";

	public static final long GUARANTEE = 100_000_000L;

	private static final byte[] DEAL = { 125, (byte) 223, (byte) 160, (byte) 234, 71, (byte) 162, (byte) 182,
			(byte) 219 };

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

	public static byte[] dealData(String payer, String recipient, String oracle, long id, long amount, long startsAt,
			long duration) {
		return deal(payer, recipient, oracle, startsAt).dealId(id).amount(amount).window(duration, 2).data();
	}

	public static byte[] proposalData(String payer, String recipient, String oracle, long id, long amount, long stake,
			long duration, long acceptDeadline) {
		return deal(payer, recipient, oracle, 0).dealId(id).amount(amount).awaitingProvider(stake)
			.acceptDeadline(acceptDeadline).window(duration, 2).data();
	}

	/** A {@code Deal} account under construction; every setter returns {@code this}. */
	public static final class Deal {

		private final String payer;

		private final String recipient;

		private final String oracle;

		private long dealId = 1;

		private long amount = 500_000_000L;

		private long stake;

		private long acceptDeadline = 1_790_086_400L;

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

		public Deal acceptDeadline(long deadline) {
			this.acceptDeadline = deadline;
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
			return ByteBuffer.allocate(8 + 96 + 32 + 1 + 24 + 2 + 12 + 1 + 4 + bits.length)
				.order(ByteOrder.LITTLE_ENDIAN)
				.put(DEAL)
				.put(Base58.decodePublicKey(payer))
				.put(Base58.decodePublicKey(recipient))
				.put(Base58.decodePublicKey(oracle))
				.putLong(dealId)
				.putLong(amount)
				.putLong(stake)
				.putLong(acceptDeadline)
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

}
