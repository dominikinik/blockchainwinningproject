package com.example.monitor.infrastructure.solana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OracleKeyTest {

	private static final HexFormat HEX = HexFormat.of();

	@Test
	void signsTheRfc8032TestVector() {
		// RFC 8032, section 7.1, TEST 1 (empty message).
		byte[] seed = HEX.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
		byte[] pub = HEX.parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
		OracleKey key = OracleKey.fromKeypairBytes(concat(seed, pub));

		assertThat(HEX.formatHex(key.sign(new byte[0]))).isEqualTo("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d8"
				+ "73e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
		assertThat(key.publicKey()).isEqualTo(pub);
		assertThat(key.address()).isEqualTo(Base58.encode(pub));
	}

	@Test
	void generatedKeysSignVerifiably() {
		OracleKey key = OracleKey.generate();
		byte[] message = { 1, 2, 3 };
		byte[] signature = key.sign(message);
		assertThat(signature).hasSize(64);
		assertThat(Ed25519.verify(key.publicKey(), message, signature)).isTrue();
		assertThat(Ed25519.verify(key.publicKey(), new byte[] { 9 }, signature)).isFalse();
		assertThat(OracleKey.generate().address()).isNotEqualTo(key.address());
	}

	@Test
	void loadOrCreateWritesASolanaKeypairFileAndReadsItBack(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("keys/oracle.json");
		OracleKey created = OracleKey.loadOrCreate(file);

		String json = Files.readString(file);
		assertThat(json).startsWith("[").endsWith("]");
		assertThat(json.split(",")).hasSize(64);
		assertThat(OracleKey.loadOrCreate(file).address()).isEqualTo(created.address());
	}

	@Test
	void loadOrCreateCreatesTheKeyFileReadableByItsOwnerOnly(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("oracle.json");
		OracleKey.loadOrCreate(file);

		assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"));
		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
	}

	@Test
	void rejectsMalformedKeypairs(@TempDir Path dir) throws Exception {
		assertThatIllegalArgumentException().isThrownBy(() -> OracleKey.fromKeypairBytes(new byte[63]));
		byte[] mismatched = concat(new byte[32], OracleKey.generate().publicKey());
		assertThatIllegalArgumentException().isThrownBy(() -> OracleKey.fromKeypairBytes(mismatched))
			.withMessageContaining("does not match");

		Path notJson = Files.writeString(dir.resolve("bad.json"), "{\"key\": 1}");
		assertThatIllegalArgumentException().isThrownBy(() -> OracleKey.loadOrCreate(notJson));
		Path outOfRange = Files.writeString(dir.resolve("range.json"), "[256]");
		assertThatIllegalArgumentException().isThrownBy(() -> OracleKey.loadOrCreate(outOfRange));
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = new byte[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

}
