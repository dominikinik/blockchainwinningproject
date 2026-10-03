package com.example.uptime.solana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class Base58Test {

	@Test
	void encodesKnownVectors() {
		assertThat(Base58.encode("Hello World!".getBytes(StandardCharsets.US_ASCII))).isEqualTo("2NEpo7TZRRrLZSi2U");
		assertThat(Base58.encode(new byte[32])).isEqualTo("11111111111111111111111111111111");
		assertThat(Base58.encode(new byte[] { 0, 0, 1 })).isEqualTo("112");
		assertThat(Base58.encode(new byte[0])).isEmpty();
	}

	@Test
	void decodesKnownVectorsAndKeepsLeadingZeros() {
		assertThat(Base58.decode("2NEpo7TZRRrLZSi2U")).asString(StandardCharsets.US_ASCII).isEqualTo("Hello World!");
		assertThat(Base58.decode("11111111111111111111111111111111")).isEqualTo(new byte[32]);
		assertThat(Base58.decode("112")).isEqualTo(new byte[] { 0, 0, 1 });
	}

	@Test
	void roundTripsAddresses() {
		String program = "EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r";
		assertThat(Base58.decodePublicKey(program)).hasSize(32);
		assertThat(Base58.encode(Base58.decodePublicKey(program))).isEqualTo(program);
	}

	@Test
	void rejectsInvalidText() {
		assertThatIllegalArgumentException().isThrownBy(() -> Base58.decode("0OIl"));
		assertThatIllegalArgumentException().isThrownBy(() -> Base58.decodePublicKey("2NEpo7TZRRrLZSi2U"))
			.withMessageContaining("not a 32-byte Solana address");
	}

}
