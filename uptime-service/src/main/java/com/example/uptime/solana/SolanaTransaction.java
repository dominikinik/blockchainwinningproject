package com.example.uptime.solana;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and signs legacy Solana transactions with a single signer, the fee payer.
 * <p>
 * Wire format: {@code shortvec(signatures) | signatures | message}, where the message is
 * {@code header(3) | shortvec(keys) | keys | blockhash | shortvec(instructions) | instructions}.
 */
public final class SolanaTransaction {

	private SolanaTransaction() {
	}

	/**
	 * An account an instruction reads or writes.
	 *
	 * @param publicKey the 32-byte address
	 * @param signer    whether the account must sign
	 * @param writable  whether the instruction may change the account
	 */
	public record AccountMeta(byte[] publicKey, boolean signer, boolean writable) {
	}

	/**
	 * One program call.
	 *
	 * @param programId the 32-byte program address
	 * @param accounts  the accounts, in the order the program expects
	 * @param data      the instruction data
	 */
	public record Instruction(byte[] programId, List<AccountMeta> accounts, byte[] data) {
	}

	/**
	 * Compiles the instructions into a message and signs it.
	 *
	 * @param feePayer     pays the fee; the only signer, so every signer account must be this key
	 * @param instructions the instructions to run, in order
	 * @param blockhash    a recent 32-byte blockhash
	 * @return the serialized signed transaction, ready for {@code sendTransaction}
	 * @throws IllegalArgumentException if an instruction needs a signer other than the fee payer
	 */
	public static byte[] signed(OracleKey feePayer, List<Instruction> instructions, byte[] blockhash) {
		byte[] message = compileMessage(feePayer.publicKey(), instructions, blockhash);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeShortVec(out, 1);
		out.writeBytes(feePayer.sign(message));
		out.writeBytes(message);
		return out.toByteArray();
	}

	/**
	 * Compiles a legacy message: merges duplicate accounts and orders them signer-writable,
	 * signer-readonly, writable, readonly, with the fee payer first.
	 *
	 * @param feePayer     the 32-byte fee payer, which signs
	 * @param instructions the instructions to run, in order
	 * @param blockhash    a recent 32-byte blockhash
	 * @return the message bytes that the signer signs
	 * @throws IllegalArgumentException if an instruction needs a signer other than the fee payer
	 */
	public static byte[] compileMessage(byte[] feePayer, List<Instruction> instructions, byte[] blockhash) {
		Map<String, AccountMeta> merged = new LinkedHashMap<>();
		merge(merged, new AccountMeta(feePayer, true, true));
		for (Instruction ix : instructions) {
			for (AccountMeta meta : ix.accounts()) {
				merge(merged, meta);
			}
			merge(merged, new AccountMeta(ix.programId(), false, false));
		}
		List<AccountMeta> keys = new ArrayList<>(merged.values());
		// Stable sort keeps the fee payer first and otherwise the first-seen order within a group.
		keys.sort(Comparator.comparingInt(SolanaTransaction::group));
		long signers = keys.stream().filter(AccountMeta::signer).count();
		if (signers != 1) {
			throw new IllegalArgumentException("Only the fee payer may sign, found " + signers + " signers");
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(1);
		out.write((int) keys.stream().filter(k -> k.signer() && !k.writable()).count());
		out.write((int) keys.stream().filter(k -> !k.signer() && !k.writable()).count());
		writeShortVec(out, keys.size());
		keys.forEach(k -> out.writeBytes(k.publicKey()));
		out.writeBytes(blockhash);
		writeShortVec(out, instructions.size());
		for (Instruction ix : instructions) {
			out.write(indexOf(keys, ix.programId()));
			writeShortVec(out, ix.accounts().size());
			ix.accounts().forEach(meta -> out.write(indexOf(keys, meta.publicKey())));
			writeShortVec(out, ix.data().length);
			out.writeBytes(ix.data());
		}
		return out.toByteArray();
	}

	private static void merge(Map<String, AccountMeta> merged, AccountMeta meta) {
		merged.merge(Arrays.toString(meta.publicKey()), meta,
				(a, b) -> new AccountMeta(a.publicKey(), a.signer() || b.signer(), a.writable() || b.writable()));
	}

	private static int group(AccountMeta meta) {
		if (meta.signer()) {
			return meta.writable() ? 0 : 1;
		}
		return meta.writable() ? 2 : 3;
	}

	private static int indexOf(List<AccountMeta> keys, byte[] key) {
		for (int i = 0; i < keys.size(); i++) {
			if (Arrays.equals(keys.get(i).publicKey(), key)) {
				return i;
			}
		}
		throw new IllegalStateException("Account missing from message");
	}

	/** Solana's compact-u16: 7 bits per byte, low bits first, high bit set when more bytes follow. */
	static void writeShortVec(ByteArrayOutputStream out, int value) {
		int rest = value;
		while (true) {
			int b = rest & 0x7f;
			rest >>= 7;
			if (rest == 0) {
				out.write(b);
				return;
			}
			out.write(b | 0x80);
		}
	}

}
