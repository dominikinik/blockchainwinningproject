package com.example.uptime.solana;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/** Ed25519 signature checks on raw 32-byte Solana public keys. */
public final class Ed25519 {

	/** DER prefix of an X.509 SubjectPublicKeyInfo for an Ed25519 key; the raw key follows it. */
	private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

	private Ed25519() {
	}

	/**
	 * Verifies a signature.
	 *
	 * @param publicKey the 32-byte public key
	 * @param message   the signed bytes
	 * @param signature the 64-byte signature
	 * @return {@code true} if the signature is valid for that key and message
	 */
	public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
		try {
			byte[] x509 = new byte[X509_PREFIX.length + publicKey.length];
			System.arraycopy(X509_PREFIX, 0, x509, 0, X509_PREFIX.length);
			System.arraycopy(publicKey, 0, x509, X509_PREFIX.length, publicKey.length);
			PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(x509));
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(key);
			verifier.update(message);
			return verifier.verify(signature);
		}
		catch (GeneralSecurityException e) {
			return false;
		}
	}

}
