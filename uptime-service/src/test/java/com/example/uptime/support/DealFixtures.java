package com.example.uptime.support;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

import com.example.uptime.solana.Base58;
import com.example.uptime.solana.OracleKey;

/** Builds {@code uptime_deal} account data and event logs the way the program writes them. */
public final class DealFixtures {

	public static final String PROGRAM_ID = "EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r";

	/** The recipient's guarantee in the deals these fixtures build, unless given explicitly. */
	public static final long GUARANTEE = 700_000_000L;

	/** The program's {@code ACCEPT_TIMEOUT_SECONDS}. */
	public static final long ACCEPT_TIMEOUT_SECONDS = 86_400;

	private static final byte[] DEAL = { 125, (byte) 223, (byte) 160, (byte) 234, 71, (byte) 162, (byte) 182,
			(byte) 219 };

	private static final byte[] DEAL_SETTLED = { 41, (byte) 213, (byte) 235, 64, 55, (byte) 168, 51, 76 };

	private static final byte[] DEAL_CANCELLED = { (byte) 229, (byte) 189, 86, (byte) 176, (byte) 134, (byte) 151, 43,
			(byte) 152 };

	private static final byte PROPOSED = 0;

	private static final byte ACTIVE = 1;

	private DealFixtures() {
	}

	/** A fresh random Base58 address. */
	public static String newAddress() {
		return OracleKey.generate().address();
	}

	/**
	 * {@code Deal} account data of an accepted deal whose window starts at {@code startsAt}, with a
	 * {@link #GUARANTEE} guarantee and a proposal deadline one {@link #ACCEPT_TIMEOUT_SECONDS} after it.
	 */
	public static byte[] dealData(String payer, String recipient, String oracle, long dealId, long amount,
			long startsAt, long durationSeconds) {
		return dealData(payer, recipient, oracle, dealId, amount, GUARANTEE, durationSeconds,
				startsAt + ACCEPT_TIMEOUT_SECONDS, startsAt, ACTIVE);
	}

	/** {@code Deal} account data of a proposal the recipient hasn't accepted yet. */
	public static byte[] proposalData(String payer, String recipient, String oracle, long dealId, long amount,
			long guarantee, long durationSeconds, long acceptDeadline) {
		return dealData(payer, recipient, oracle, dealId, amount, guarantee, durationSeconds, acceptDeadline, 0,
				PROPOSED);
	}

	/**
	 * {@code Deal} account data (154 bytes): discriminator, payer, recipient, oracle, deal_id, amount,
	 * guarantee, duration_seconds, accept_deadline, starts_at, status, bump.
	 */
	public static byte[] dealData(String payer, String recipient, String oracle, long dealId, long amount,
			long guarantee, long durationSeconds, long acceptDeadline, long startsAt, byte status) {
		return ByteBuffer.allocate(8 + 96 + 8 * 6 + 1 + 1)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(DEAL)
			.put(Base58.decodePublicKey(payer))
			.put(Base58.decodePublicKey(recipient))
			.put(Base58.decodePublicKey(oracle))
			.putLong(dealId)
			.putLong(amount)
			.putLong(guarantee)
			.putLong(durationSeconds)
			.putLong(acceptDeadline)
			.putLong(startsAt)
			.put(status)
			.put((byte) 254)
			.array();
	}

	/**
	 * The {@code Program data:} log line of a {@code DealCancelled} event cancelled by the payer, with no
	 * guarantee refunded.
	 */
	public static String dealCancelledLog(String deal, String payer, long amount) {
		byte[] event = ByteBuffer.allocate(8 + 32 + 32 + 8 + 32 + 8)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(DEAL_CANCELLED)
			.put(Base58.decodePublicKey(deal))
			.put(Base58.decodePublicKey(payer))
			.putLong(amount)
			.put(Base58.decodePublicKey(payer))
			.putLong(0)
			.array();
		return "Program data: " + Base64.getEncoder().encodeToString(event);
	}

	/** The {@code Program data:} log line of a {@code DealSettled} event with a {@link #GUARANTEE} guarantee. */
	public static String dealSettledLog(String deal, long up, long total, boolean paid, long amount) {
		byte[] event = ByteBuffer.allocate(8 + 32 + 8 + 8 + 1 + 8 + 8)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(DEAL_SETTLED)
			.put(Base58.decodePublicKey(deal))
			.putLong(up)
			.putLong(total)
			.put((byte) (paid ? 1 : 0))
			.putLong(amount)
			.putLong(GUARANTEE)
			.array();
		return "Program data: " + Base64.getEncoder().encodeToString(event);
	}

}
