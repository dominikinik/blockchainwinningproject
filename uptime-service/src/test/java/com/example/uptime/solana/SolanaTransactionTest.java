package com.example.uptime.solana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.uptime.solana.SolanaTransaction.AccountMeta;
import com.example.uptime.solana.SolanaTransaction.Instruction;

class SolanaTransactionTest {

	private final OracleKey payer = OracleKey.generate();

	private final byte[] a = key(1);

	private final byte[] b = key(2);

	private final byte[] program = key(9);

	private final byte[] blockhash = key(7);

	@Test
	void compilesHeaderKeysBlockhashAndInstruction() {
		Instruction ix = new Instruction(program, List.of(new AccountMeta(payer.publicKey(), true, false),
				new AccountMeta(b, false, false), new AccountMeta(a, false, true)), new byte[] { 5, 6 });

		byte[] msg = SolanaTransaction.compileMessage(payer.publicKey(), List.of(ix), blockhash);

		// 1 signer, 0 read-only signers, 2 read-only non-signers (b and the program).
		assertThat(Arrays.copyOfRange(msg, 0, 3)).containsExactly(1, 0, 2);
		assertThat(msg[3]).isEqualTo((byte) 4);
		// Order: fee payer, writable a, then read-only b and the program in first-seen order.
		assertThat(keyAt(msg, 0)).isEqualTo(payer.publicKey());
		assertThat(keyAt(msg, 1)).isEqualTo(a);
		assertThat(keyAt(msg, 2)).isEqualTo(b);
		assertThat(keyAt(msg, 3)).isEqualTo(program);
		int at = 4 + 4 * 32;
		assertThat(Arrays.copyOfRange(msg, at, at + 32)).isEqualTo(blockhash);
		// One instruction: program index 3, accounts [payer=0, b=2, a=1], data [5, 6].
		assertThat(Arrays.copyOfRange(msg, at + 32, msg.length)).containsExactly(1, 3, 3, 0, 2, 1, 2, 5, 6);
	}

	@Test
	void mergesDuplicateAccountsWithTheStrongestFlags() {
		Instruction ix = new Instruction(program,
				List.of(new AccountMeta(a, false, false), new AccountMeta(a, false, true),
						new AccountMeta(payer.publicKey(), false, false)),
				new byte[0]);

		byte[] msg = SolanaTransaction.compileMessage(payer.publicKey(), List.of(ix), blockhash);

		assertThat(Arrays.copyOfRange(msg, 0, 4)).containsExactly(1, 0, 1, 3);
		assertThat(keyAt(msg, 1)).isEqualTo(a);
		int at = 4 + 3 * 32 + 32;
		assertThat(Arrays.copyOfRange(msg, at, msg.length)).containsExactly(1, 2, 3, 1, 1, 0, 0);
	}

	@Test
	void rejectsSignersOtherThanTheFeePayer() {
		Instruction ix = new Instruction(program, List.of(new AccountMeta(a, true, true)), new byte[0]);
		assertThatIllegalArgumentException()
			.isThrownBy(() -> SolanaTransaction.compileMessage(payer.publicKey(), List.of(ix), blockhash));
	}

	@Test
	void signedTransactionCarriesOneValidSignatureOverTheMessage() {
		Instruction ix = new Instruction(program, List.of(new AccountMeta(a, false, true)), new byte[] { 1 });

		byte[] tx = SolanaTransaction.signed(payer, List.of(ix), blockhash);

		byte[] message = SolanaTransaction.compileMessage(payer.publicKey(), List.of(ix), blockhash);
		assertThat(tx[0]).isEqualTo((byte) 1);
		assertThat(Arrays.copyOfRange(tx, 65, tx.length)).isEqualTo(message);
		assertThat(Ed25519.verify(payer.publicKey(), message, Arrays.copyOfRange(tx, 1, 65))).isTrue();
	}

	@Test
	void shortVecUsesSevenBitsPerByte() {
		assertThat(shortVec(0)).containsExactly(0);
		assertThat(shortVec(127)).containsExactly(0x7f);
		assertThat(shortVec(128)).containsExactly(0x80, 0x01);
		assertThat(shortVec(16384)).containsExactly(0x80, 0x80, 0x01);
	}

	private static byte[] shortVec(int value) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SolanaTransaction.writeShortVec(out, value);
		return out.toByteArray();
	}

	private static byte[] keyAt(byte[] msg, int index) {
		return Arrays.copyOfRange(msg, 4 + index * 32, 4 + (index + 1) * 32);
	}

	private static byte[] key(int fill) {
		byte[] k = new byte[32];
		Arrays.fill(k, (byte) fill);
		return k;
	}

}
