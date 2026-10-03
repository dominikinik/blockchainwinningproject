package com.example.uptime.deal;

import static com.example.uptime.support.DealFixtures.PROGRAM_ID;
import static com.example.uptime.support.DealFixtures.dealData;
import static com.example.uptime.support.DealFixtures.dealSettledLog;
import static com.example.uptime.support.DealFixtures.newAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.uptime.deal.DealProgram.DealAccount;
import com.example.uptime.solana.Base58;
import com.example.uptime.solana.SolanaTransaction.AccountMeta;
import com.example.uptime.solana.SolanaTransaction.Instruction;

class DealProgramTest {

	private final String payer = newAddress();

	private final String recipient = newAddress();

	private final String oracle = newAddress();

	@Test
	void decodesDealAccounts() {
		DealAccount deal = DealProgram.decodeDeal(dealData(payer, recipient, oracle, 42, 2_000_000_000L));
		assertThat(deal).isEqualTo(new DealAccount(payer, recipient, oracle, 42, 2_000_000_000L));
	}

	@Test
	void rejectsOtherAccounts() {
		byte[] data = dealData(payer, recipient, oracle, 1, 1);
		data[0] ^= 1;
		assertThatIllegalArgumentException().isThrownBy(() -> DealProgram.decodeDeal(data));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DealProgram.decodeDeal(Arrays.copyOf(dealData(payer, recipient, oracle, 1, 1), 100)));
	}

	@Test
	void buildsSettleDealInstruction() {
		String deal = newAddress();
		Instruction ix = DealProgram.settleInstruction(PROGRAM_ID, oracle, deal,
				new DealAccount(payer, recipient, oracle, 1, 5), 9, 10);

		assertThat(ix.programId()).isEqualTo(Base58.decodePublicKey(PROGRAM_ID));
		assertThat(ix.accounts()).extracting(m -> Base58.encode(m.publicKey()))
			.containsExactly(oracle, deal, payer, recipient);
		assertThat(ix.accounts()).extracting(AccountMeta::signer).containsExactly(true, false, false, false);
		assertThat(ix.accounts()).extracting(AccountMeta::writable).containsExactly(false, true, true, true);
		ByteBuffer data = ByteBuffer.wrap(ix.data()).order(ByteOrder.LITTLE_ENDIAN);
		assertThat(Arrays.copyOf(ix.data(), 8)).containsExactly(28, 10, 168, 174, 203, 149, 134, 54);
		assertThat(data.getLong(8)).isEqualTo(9);
		assertThat(data.getLong(16)).isEqualTo(10);
	}

	@Test
	void readsTheVerdictFromDealSettledEvents() {
		String deal = newAddress();
		assertThat(DealProgram.paidToRecipient(List.of("Program log: Instruction: SettleDeal",
				dealSettledLog(deal, 10, 10, true, 5)))).isTrue();
		assertThat(DealProgram.paidToRecipient(List.of(dealSettledLog(deal, 9, 10, false, 5)))).isFalse();
	}

	@Test
	void ignoresOtherLogLines() {
		assertThat(DealProgram.paidToRecipient(List.of())).isNull();
		assertThat(DealProgram.paidToRecipient(List.of("Program data: not-base64!", "Program data: AAAA",
				"Program log: hi"))).isNull();
	}

}
