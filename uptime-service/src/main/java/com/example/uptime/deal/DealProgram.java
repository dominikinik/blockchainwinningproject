package com.example.uptime.deal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import com.example.uptime.solana.Base58;
import com.example.uptime.solana.SolanaTransaction.AccountMeta;
import com.example.uptime.solana.SolanaTransaction.Instruction;

/**
 * Binary layout of the {@code uptime_deal} Anchor program (see {@code uptime-deal/}): the {@code Deal}
 * account, the {@code settle_deal} instruction and the {@code DealSettled} and {@code DealCancelled}
 * events. The discriminators are the first 8 bytes of {@code sha256("account:Deal")},
 * {@code sha256("global:settle_deal")}, {@code sha256("event:DealSettled")} and
 * {@code sha256("event:DealCancelled")}, as listed in the program's IDL.
 */
public final class DealProgram {

	static final byte[] DEAL_DISCRIMINATOR = { 125, (byte) 223, (byte) 160, (byte) 234, 71, (byte) 162, (byte) 182,
			(byte) 219 };

	static final byte[] SETTLE_DEAL_DISCRIMINATOR = { 28, 10, (byte) 168, (byte) 174, (byte) 203, (byte) 149,
			(byte) 134, 54 };

	static final byte[] DEAL_SETTLED_DISCRIMINATOR = { 41, (byte) 213, (byte) 235, 64, 55, (byte) 168, 51, 76 };

	static final byte[] DEAL_CANCELLED_DISCRIMINATOR = { (byte) 229, (byte) 189, 86, (byte) 176, (byte) 134,
			(byte) 151, 43, (byte) 152 };

	/** Discriminator, payer, recipient, oracle, deal_id, amount_lamports, starts_at, duration_seconds, bump. */
	private static final int DEAL_ACCOUNT_SIZE = 8 + 32 * 3 + 8 + 8 + 8 + 8 + 1;

	private static final String EVENT_LOG_PREFIX = "Program data: ";

	private DealProgram() {
	}

	/**
	 * A decoded {@code Deal} account.
	 *
	 * @param payer          Base58 wallet that funded the escrow
	 * @param recipient      Base58 wallet paid when uptime is above 99%
	 * @param oracle         Base58 key allowed to settle
	 * @param dealId         the payer-chosen id
	 * @param amountLamports the escrowed lamports
	 * @param startsAt        chain time at which the deal was created; the window starts here
	 * @param durationSeconds length of the window in seconds, as stored on chain
	 */
	public record DealAccount(String payer, String recipient, String oracle, long dealId, long amountLamports,
			Instant startsAt, long durationSeconds) {
	}

	/**
	 * How a deal account was closed.
	 *
	 * @param cancelled         {@code true} if the payer cancelled it ({@code DealCancelled}), {@code false}
	 *                          if it was settled ({@code DealSettled})
	 * @param paidToRecipient   for a settlement, whether the recipient was paid; {@code null} if cancelled
	 * @param upSeconds         for a settlement, the up seconds the oracle reported; {@code null} if cancelled
	 * @param totalSeconds      for a settlement, the window length the oracle reported; {@code null} if
	 *                          cancelled
	 */
	public record Outcome(boolean cancelled, Boolean paidToRecipient, Long upSeconds, Long totalSeconds) {
	}

	/**
	 * Decodes {@code Deal} account data.
	 *
	 * @param data the raw account data
	 * @return the decoded deal
	 * @throws IllegalArgumentException if the data is too short or isn't a {@code Deal}
	 */
	public static DealAccount decodeDeal(byte[] data) {
		if (data.length < DEAL_ACCOUNT_SIZE || !Arrays.equals(data, 0, 8, DEAL_DISCRIMINATOR, 0, 8)) {
			throw new IllegalArgumentException("Account is not an uptime_deal Deal");
		}
		ByteBuffer buf = ByteBuffer.wrap(data, 8, data.length - 8).order(ByteOrder.LITTLE_ENDIAN);
		return new DealAccount(readKey(buf), readKey(buf), readKey(buf), buf.getLong(), buf.getLong(),
				Instant.ofEpochSecond(buf.getLong()), buf.getLong());
	}

	/**
	 * Builds the {@code settle_deal} instruction.
	 *
	 * @param programId    Base58 program id
	 * @param oracle       Base58 oracle, which signs
	 * @param dealAddress  Base58 deal address
	 * @param deal         the decoded deal, for its payer and recipient
	 * @param upSeconds    seconds the service was up in the window
	 * @param totalSeconds length of the window in seconds
	 * @return the instruction, with accounts in the program's order: oracle, deal, payer, recipient
	 */
	public static Instruction settleInstruction(String programId, String oracle, String dealAddress, DealAccount deal,
			long upSeconds, long totalSeconds) {
		byte[] data = ByteBuffer.allocate(24)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(SETTLE_DEAL_DISCRIMINATOR)
			.putLong(upSeconds)
			.putLong(totalSeconds)
			.array();
		List<AccountMeta> accounts = List.of(new AccountMeta(Base58.decodePublicKey(oracle), true, false),
				new AccountMeta(Base58.decodePublicKey(dealAddress), false, true),
				new AccountMeta(Base58.decodePublicKey(deal.payer()), false, true),
				new AccountMeta(Base58.decodePublicKey(deal.recipient()), false, true));
		return new Instruction(Base58.decodePublicKey(programId), accounts, data);
	}

	/**
	 * Finds the {@code DealSettled} event in transaction logs and reads who got the escrow.
	 *
	 * @param logs the transaction's log lines
	 * @return {@code true} if the recipient was paid, {@code false} if the payer was refunded, or
	 *         {@code null} if the logs hold no {@code DealSettled} event
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
			// Discriminator, deal, up_seconds, total_seconds, paid_to_recipient, amount_lamports.
			if (event.length >= 8 + 32 + 8 + 8 + 1 && Arrays.equals(event, 0, 8, DEAL_SETTLED_DISCRIMINATOR, 0, 8)) {
				long up = buf.getLong();
				long total = buf.getLong();
				return new Outcome(false, buf.get() != 0, up, total);
			}
			// Discriminator, deal, payer, amount_lamports.
			if (event.length >= 8 + 32 + 32 + 8 && Arrays.equals(event, 0, 8, DEAL_CANCELLED_DISCRIMINATOR, 0, 8)) {
				return new Outcome(true, null, null, null);
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
