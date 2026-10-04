package com.example.monitor.infrastructure.solana;

import static com.example.monitor.support.DealFixtures.PROGRAM_ID;
import static com.example.monitor.support.DealFixtures.dealCancelledLog;
import static com.example.monitor.support.DealFixtures.dealData;
import static com.example.monitor.support.DealFixtures.dealSettledLog;
import static com.example.monitor.support.DealFixtures.newAddress;
import static com.example.monitor.support.DealFixtures.proposalData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.monitor.infrastructure.solana.DealProgram.DealAccount;
import com.example.monitor.infrastructure.solana.DealProgram.DealStatus;
import com.example.monitor.infrastructure.solana.Base58;
import com.example.monitor.infrastructure.solana.SolanaTransaction.AccountMeta;
import com.example.monitor.infrastructure.solana.SolanaTransaction.Instruction;

class DealProgramTest {

	private final String payer = newAddress();

	private final String recipient = newAddress();

	private final String oracle = newAddress();

	@Test
	void decodesAcceptedDeals() {
		byte[] data = dealData(payer, recipient, oracle, 42, 2_000_000_000L, 3_000_000_000L, 600, 1_790_086_400L,
				1_790_000_000L, (byte) 1);
		assertThat(data).hasSize(154);
		DealAccount deal = DealProgram.decodeDeal(data);
		assertThat(deal).isEqualTo(new DealAccount(payer, recipient, oracle, 42, 2_000_000_000L, 3_000_000_000L, 600,
				Instant.ofEpochSecond(1_790_086_400L), Instant.ofEpochSecond(1_790_000_000L), DealStatus.ACTIVE));
	}

	@Test
	void decodesProposalsWithoutAWindow() {
		DealAccount deal = DealProgram.decodeDeal(proposalData(payer, recipient, oracle, 7, 5, 6, 10, 1_790_086_400L));
		assertThat(deal).isEqualTo(new DealAccount(payer, recipient, oracle, 7, 5, 6, 10,
				Instant.ofEpochSecond(1_790_086_400L), null, DealStatus.PROPOSED));
	}

	@Test
	void rejectsOtherAccounts() {
		byte[] data = dealData(payer, recipient, oracle, 1, 1, 0, 1);
		data[0] ^= 1;
		assertThatIllegalArgumentException().isThrownBy(() -> DealProgram.decodeDeal(data));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DealProgram.decodeDeal(Arrays.copyOf(dealData(payer, recipient, oracle, 1, 1, 0, 1), 153)));
		// The 137-byte layout from before deals needed acceptance.
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DealProgram.decodeDeal(Arrays.copyOf(dealData(payer, recipient, oracle, 1, 1, 0, 1), 137)));
	}

	@Test
	void rejectsAnUnknownStatus() {
		byte[] data = dealData(payer, recipient, oracle, 1, 1, 1, 1, 1, 1, (byte) 2);
		assertThatIllegalArgumentException().isThrownBy(() -> DealProgram.decodeDeal(data))
			.withMessageContaining("unknown status");
	}

	@Test
	void buildsSettleDealInstruction() {
		String deal = newAddress();
		Instruction ix = DealProgram.settleInstruction(PROGRAM_ID, oracle, deal, new DealAccount(payer, recipient,
				oracle, 1, 5, 6, 10, Instant.EPOCH, Instant.EPOCH, DealStatus.ACTIVE), 9, 10);

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
	void closedByFindsSettlementsAndCancellationsOfTheGivenDealOnly() {
		String deal = newAddress();
		String other = newAddress();

		DealProgram.Outcome settled = DealProgram.closedBy(
				List.of(dealSettledLog(other, 1, 1, false, 5), dealSettledLog(deal, 9, 10, true, 5)), deal);
		assertThat(settled).isEqualTo(new DealProgram.Outcome(false, true, 9L, 10L));

		assertThat(DealProgram.closedBy(List.of(dealCancelledLog(deal, payer, 5)), deal))
			.isEqualTo(new DealProgram.Outcome(true, null, null, null));
		assertThat(DealProgram.closedBy(List.of(dealCancelledLog(other, payer, 5), dealSettledLog(other, 1, 1, true, 5)),
				deal))
			.isNull();
		assertThat(DealProgram.closedBy(List.of(dealCancelledLog(other, payer, 5)), null).cancelled()).isTrue();
		assertThat(DealProgram.paidToRecipient(List.of(dealCancelledLog(deal, payer, 5)))).isNull();
	}

	@Test
	void ignoresOtherLogLines() {
		assertThat(DealProgram.paidToRecipient(List.of())).isNull();
		assertThat(DealProgram.paidToRecipient(List.of("Program data: not-base64!", "Program data: AAAA",
				"Program log: hi"))).isNull();
		assertThat(DealProgram.closedBy(List.of("Program data: AAAA"), newAddress())).isNull();
	}

}
