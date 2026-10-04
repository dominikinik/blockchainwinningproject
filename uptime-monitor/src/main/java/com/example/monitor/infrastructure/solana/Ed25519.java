package com.example.monitor.infrastructure.solana;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/** Ed25519 signature checks on raw 32-byte Solana public keys. */
public final class Ed25519 {

	/**
	 * Fixed ASN.1 DER header that the JDK needs in front of a raw 32-byte Ed25519 public key (X.509
	 * SubjectPublicKeyInfo). It is not a key or a secret: it is the same for every Ed25519 key and
	 * defined by RFC 8410. {@code 30 2a} SEQUENCE(42) · {@code 30 05 06 03 2b 65 70} AlgorithmIdentifier
	 * with OID 1.3.101.112 (Ed25519) · {@code 03 21 00} BIT STRING(33) with no unused bits, then the key.
	 * The only secret is the oracle's private key, loaded from {@code monitor.blockchain.oracle-keypair}.
	 */
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
