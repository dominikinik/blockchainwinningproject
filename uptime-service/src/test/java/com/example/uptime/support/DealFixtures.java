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

	private DealFixtures() {
	}

	/** A fresh random Base58 address. */
	public static String newAddress() {
		return OracleKey.generate().address();
	}

	private static final byte[] DEAL_CANCELLED = { (byte) 229, (byte) 189, 86, (byte) 176, (byte) 134, (byte) 151, 43,
			(byte) 152 };

	/**
	 * {@code Deal} account data (137 bytes): discriminator, payer, recipient, oracle, deal_id, amount,
	 * starts_at, duration_seconds, bump.
	 */
	public static byte[] dealData(String payer, String recipient, String oracle, long dealId, long amount,
			long startsAt, long durationSeconds) {
		return ByteBuffer.allocate(8 + 96 + 8 + 8 + 8 + 8 + 1)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(DEAL)
			.put(Base58.decodePublicKey(payer))
			.put(Base58.decodePublicKey(recipient))
			.put(Base58.decodePublicKey(oracle))
			.putLong(dealId)
			.putLong(amount)
			.putLong(startsAt)
			.putLong(durationSeconds)
			.put((byte) 254)
			.array();
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
	public static String dealSettledLog(String deal, long up, long total, boolean paid, long amount) {
		byte[] event = ByteBuffer.allocate(8 + 32 + 8 + 8 + 1 + 8)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(DEAL_SETTLED)
			.put(Base58.decodePublicKey(deal))
			.putLong(up)
			.putLong(total)
			.put((byte) (paid ? 1 : 0))
			.putLong(amount)
			.array();
		return "Program data: " + Base64.getEncoder().encodeToString(event);
	}

}
