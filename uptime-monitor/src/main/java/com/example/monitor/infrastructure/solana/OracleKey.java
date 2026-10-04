package com.example.monitor.infrastructure.solana;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.StringJoiner;

/**
 * The Ed25519 key this service signs deal settlements with, stored in the Solana CLI keypair format
 * (a JSON array of 64 bytes: the 32-byte seed followed by the 32-byte public key).
 */
public final class OracleKey {

	private final PrivateKey privateKey;

	private final byte[] publicKey;

	private OracleKey(byte[] seed, byte[] publicKey) {
		try {
			this.privateKey = KeyFactory.getInstance("Ed25519")
				.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Ed25519 is not available", e);
		}
		this.publicKey = publicKey.clone();
		if (!Ed25519.verify(this.publicKey, new byte[] { 1 }, sign(new byte[] { 1 }))) {
			throw new IllegalArgumentException("Keypair public key does not match its secret key");
		}
	}

	/**
	 * Loads the keypair file, creating it with a fresh key when it doesn't exist.
	 *
	 * @param path a Solana CLI keypair file
	 * @return the key in that file
	 * @throws IOException if the file can't be read or written
	 * @throws IllegalArgumentException if the file isn't a valid 64-byte keypair
	 */
	public static OracleKey loadOrCreate(Path path) throws IOException {
		if (Files.exists(path)) {
			return fromKeypairBytes(parseJsonBytes(Files.readString(path)));
		}
		OracleKey key = generate();
		if (path.toAbsolutePath().getParent() != null) {
			Files.createDirectories(path.toAbsolutePath().getParent());
		}
		writeOwnerOnly(path, key.toKeypairJson());
		return key;
	}

	/** Creates the file readable by its owner only where the filesystem has POSIX permissions. */
	private static void writeOwnerOnly(Path path, String content) throws IOException {
		if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			Files.writeString(path, content);
		}
		else {
			Files.writeString(path, content);
		}
	}

	/**
	 * Generates a new random key.
	 *
	 * @return a key that exists only in memory
	 */
	public static OracleKey generate() {
		try {
			KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
			byte[] seed = ((EdECPrivateKey) pair.getPrivate()).getBytes().orElseThrow();
			byte[] x509 = pair.getPublic().getEncoded();
			return new OracleKey(seed, Arrays.copyOfRange(x509, x509.length - 32, x509.length));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Ed25519 is not available", e);
		}
	}

	/**
	 * Builds a key from the 64 bytes of a Solana keypair.
	 *
	 * @param keypair the 32-byte seed followed by the 32-byte public key
	 * @return the key
	 * @throws IllegalArgumentException if the length is wrong or the halves don't match
	 */
	public static OracleKey fromKeypairBytes(byte[] keypair) {
		if (keypair.length != 64) {
			throw new IllegalArgumentException("A Solana keypair has 64 bytes, got " + keypair.length);
		}
		return new OracleKey(Arrays.copyOfRange(keypair, 0, 32), Arrays.copyOfRange(keypair, 32, 64));
	}

	/**
	 * Signs a message (a serialized transaction message).
	 *
	 * @param message the bytes to sign
	 * @return the 64-byte Ed25519 signature
	 */
	public byte[] sign(byte[] message) {
		try {
			Signature signature = Signature.getInstance("Ed25519");
			signature.initSign(privateKey);
			signature.update(message);
			return signature.sign();
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Ed25519 signing failed", e);
		}
	}

	/**
	 * Returns the public key.
	 *
	 * @return a copy of the 32 public-key bytes
	 */
	public byte[] publicKey() {
		return publicKey.clone();
	}

	/**
	 * Returns the public key as an address.
	 *
	 * @return the Base58 Solana address of this key
	 */
	public String address() {
		return Base58.encode(publicKey);
	}

	private String toKeypairJson() {
		byte[] seed = ((EdECPrivateKey) privateKey).getBytes().orElseThrow();
		StringJoiner json = new StringJoiner(",", "[", "]");
		for (byte b : seed) {
			json.add(Integer.toString(b & 0xff));
		}
		for (byte b : publicKey) {
			json.add(Integer.toString(b & 0xff));
		}
		return json.toString();
	}

	private static byte[] parseJsonBytes(String json) {
		String body = json.strip();
		if (!body.startsWith("[") || !body.endsWith("]")) {
			throw new IllegalArgumentException("Keypair file must be a JSON array of bytes");
		}
		String[] parts = body.substring(1, body.length() - 1).split(",");
		byte[] out = new byte[parts.length];
		for (int i = 0; i < parts.length; i++) {
			int value = Integer.parseInt(parts[i].strip());
			if (value < 0 || value > 255) {
				throw new IllegalArgumentException("Keypair byte out of range: " + value);
			}
			out[i] = (byte) value;
		}
		return out;
	}

}
