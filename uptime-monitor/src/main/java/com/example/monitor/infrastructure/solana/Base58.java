package com.example.monitor.infrastructure.solana;

import java.math.BigInteger;
import java.util.Arrays;

/** Bitcoin-alphabet Base58, the text form of Solana addresses and transaction signatures. */
public final class Base58 {

	private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

	private static final BigInteger BASE = BigInteger.valueOf(58);

	private Base58() {
	}

	/**
	 * Encodes bytes as Base58.
	 *
	 * @param bytes the raw bytes; each leading zero byte becomes a leading {@code '1'}
	 * @return the Base58 text
	 */
	public static String encode(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		BigInteger n = new BigInteger(1, bytes);
		while (n.signum() > 0) {
			BigInteger[] qr = n.divideAndRemainder(BASE);
			sb.append(ALPHABET.charAt(qr[1].intValue()));
			n = qr[0];
		}
		for (int i = 0; i < bytes.length && bytes[i] == 0; i++) {
			sb.append('1');
		}
		return sb.reverse().toString();
	}

	/**
	 * Decodes Base58 text.
	 *
	 * @param text the Base58 text
	 * @return the raw bytes
	 * @throws IllegalArgumentException if {@code text} contains a character outside the alphabet
	 */
	public static byte[] decode(String text) {
		BigInteger n = BigInteger.ZERO;
		for (char c : text.toCharArray()) {
			int digit = ALPHABET.indexOf(c);
			if (digit < 0) {
				throw new IllegalArgumentException("Invalid Base58 character '" + c + "'");
			}
			n = n.multiply(BASE).add(BigInteger.valueOf(digit));
		}
		byte[] magnitude = n.signum() == 0 ? new byte[0] : n.toByteArray();
		if (magnitude.length > 0 && magnitude[0] == 0) {
			magnitude = Arrays.copyOfRange(magnitude, 1, magnitude.length);
		}
		int zeros = 0;
		while (zeros < text.length() && text.charAt(zeros) == '1') {
			zeros++;
		}
		byte[] out = new byte[zeros + magnitude.length];
		System.arraycopy(magnitude, 0, out, zeros, magnitude.length);
		return out;
	}

	/**
	 * Decodes a Solana address and checks its length.
	 *
	 * @param address the Base58 address
	 * @return the 32 public-key bytes
	 * @throws IllegalArgumentException if the text is not Base58 or not 32 bytes long
	 */
	public static byte[] decodePublicKey(String address) {
		byte[] key = decode(address);
		if (key.length != 32) {
			throw new IllegalArgumentException("'" + address + "' is not a 32-byte Solana address");
		}
		return key;
	}

}
