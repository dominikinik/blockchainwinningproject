package com.example.monitor.infrastructure.solana;

import static com.example.monitor.support.DealFixtures.PROGRAM_ID;
import static com.example.monitor.support.DealFixtures.deal;
import static com.example.monitor.support.DealFixtures.newAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.monitor.infrastructure.solana.DealProgram.DealAccount;
import com.example.monitor.infrastructure.solana.Base58;
import com.example.monitor.infrastructure.solana.SolanaTransaction.AccountMeta;
import com.example.monitor.infrastructure.solana.SolanaTransaction.Instruction;

class DealProgramTest {

	private final String payer = newAddress();

	private final String recipient = newAddress();

	private final String oracle = newAddress();

	@Test
	void decodesActiveDealAccountsWithTheirCounters() {
		byte[] data = deal(payer, recipient, oracle, 1_790_000_000L).dealId(42)
			.amount(2_000_000_000L)
			.window(600, 60)
			.minBps(9_950)
			.recorded(new int[] { 0, 1, 9 }, true)
			.recorded(new int[] { 4 }, false)
			.data();
		assertThat(data).hasSize(180 + 2);

		DealAccount deal = DealProgram.decodeDeal(data);
		assertThat(deal.payer()).isEqualTo(payer);
		assertThat(deal.recipient()).isEqualTo(recipient);
		assertThat(deal.oracle()).isEqualTo(oracle);
		assertThat(deal.dealId()).isEqualTo(42);
		assertThat(deal.amountLamports()).isEqualTo(2_000_000_000L);
		assertThat(deal.providerStakeLamports()).isZero();
		assertThat(deal.acceptDeadline()).isEqualTo(Instant.ofEpochSecond(1_790_086_400L));
		assertThat(deal.active()).isTrue();
		assertThat(deal.startsAt()).isEqualTo(Instant.ofEpochSecond(1_790_000_000L));
		assertThat(deal.endsAt()).isEqualTo(Instant.ofEpochSecond(1_790_000_600L));
		assertThat(deal.checkIntervalSeconds()).isEqualTo(60);
		assertThat(deal.minUptimeBps()).isEqualTo(9_950);
		assertThat(deal.totalRounds()).isEqualTo(10);
		assertThat(deal.upChecks()).isEqualTo(3);
		assertThat(deal.downChecks()).isEqualTo(1);
		assertThat(List.of(0, 1, 4, 9)).allMatch(deal::isRecorded);
		assertThat(List.of(2, 3, 5, 8, 16, -1)).noneMatch(deal::isRecorded);
	}

	@Test
	void decodesDealsAwaitingTheProvider() {
		DealAccount deal = DealProgram.decodeDeal(deal(payer, recipient, oracle, 0).awaitingProvider(7).data());
		assertThat(deal.active()).isFalse();
		assertThat(deal.providerStakeLamports()).isEqualTo(7);
		assertThat(deal.startsAt()).isNull();
		assertThat(deal.endsAt()).isNull();
	}

	@Test
	void rejectsOtherOrTruncatedAccounts() {
		byte[] data = deal(payer, recipient, oracle, 0).data();
		byte[] wrongDiscriminator = data.clone();
		wrongDiscriminator[0] ^= 1;
		assertThatIllegalArgumentException().isThrownBy(() -> DealProgram.decodeDeal(wrongDiscriminator));
		assertThatIllegalArgumentException().isThrownBy(() -> DealProgram.decodeDeal(Arrays.copyOf(data, 179)));
		assertThatIllegalArgumentException().isThrownBy(() -> DealProgram.decodeDeal(Arrays.copyOf(data, 180)))
			.withMessageContaining("bitmap");
	}

	@Test
	void buildsRecordObservationInstruction() {
		String deal = newAddress();
		Instruction ix = DealProgram.observationInstruction(PROGRAM_ID, oracle, deal, 258, true);

		assertThat(ix.programId()).isEqualTo(Base58.decodePublicKey(PROGRAM_ID));
		assertThat(ix.accounts()).extracting(m -> Base58.encode(m.publicKey())).containsExactly(oracle, deal);
		assertThat(ix.accounts()).extracting(AccountMeta::signer).containsExactly(true, false);
		assertThat(ix.accounts()).extracting(AccountMeta::writable).containsExactly(false, true);
		assertThat(Arrays.copyOf(ix.data(), 8)).containsExactly(37, 148, 41, 216, 83, 104, 162, 96);
		assertThat(ByteBuffer.wrap(ix.data()).order(ByteOrder.LITTLE_ENDIAN).getInt(8)).isEqualTo(258);
		assertThat(ix.data()).hasSize(13);
		assertThat(ix.data()[12]).isEqualTo((byte) 1);
		assertThat(DealProgram.observationInstruction(PROGRAM_ID, oracle, deal, 0, false).data()[12]).isZero();
	}

}
