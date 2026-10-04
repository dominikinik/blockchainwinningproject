package com.example.monitor.infrastructure.solana;

import static com.example.monitor.support.DealFixtures.PROGRAM_ID;
import static com.example.monitor.support.DealFixtures.deal;
import static com.example.monitor.support.DealFixtures.newAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.monitor.domain.DealChain.ActiveDeal;
import com.example.monitor.infrastructure.solana.SolanaRpc.AccountInfo;
import com.example.monitor.infrastructure.solana.SolanaRpc.ProgramAccount;
import com.example.monitor.infrastructure.solana.SolanaRpc.SolanaRpcException;

class SolanaDealChainTest {

	private final SolanaRpc rpc = mock(SolanaRpc.class);

	private final OracleKey oracle = OracleKey.generate();

	private final SolanaDealChain chain = new SolanaDealChain(rpc, oracle, PROGRAM_ID, "http://rpc.test", 100, 1_000);

	private final String payer = newAddress();

	private final String recipient = newAddress();

	@Test
	void describesWhereDealsMustBeCreated() {
		assertThat(chain.programId()).isEqualTo(PROGRAM_ID);
		assertThat(chain.oracleAddress()).isEqualTo(oracle.address());
		assertThat(chain.rpcUrl()).isEqualTo("http://rpc.test");
	}

	@Test
	void listsOnlyAcceptedDealsThatNameThisOracle() {
		byte[] active = deal(payer, recipient, oracle.address(), 1_790_000_000L).window(10, 2)
			.recorded(new int[] { 0 }, true)
			.data();
		byte[] proposal = deal(payer, recipient, oracle.address(), 0).awaitingProvider(5).data();
		byte[] foreign = deal(payer, recipient, newAddress(), 1_790_000_000L).data();
		when(rpc.getProgramAccounts(PROGRAM_ID, DealProgram.ORACLE_OFFSET, oracle.address())).thenReturn(List.of(
				account("Active", active), account("Proposal", proposal), account("Foreign", foreign),
				account("Junk", new byte[] { 1, 2, 3 })));

		List<ActiveDeal> deals = chain.activeDeals();

		assertThat(deals).hasSize(1);
		ActiveDeal deal = deals.get(0);
		assertThat(deal.address()).isEqualTo("Active");
		assertThat(deal.startsAt()).isEqualTo(Instant.ofEpochSecond(1_790_000_000L));
		assertThat(deal.checkIntervalSeconds()).isEqualTo(2);
		assertThat(deal.totalRounds()).isEqualTo(5);
		assertThat(deal.isRecorded(0)).isTrue();
		assertThat(deal.isRecorded(1)).isFalse();
	}

	@Test
	void sendsARecordObservationSignedByTheOracle() {
		String dealAddress = newAddress();
		when(rpc.getLatestBlockhash()).thenReturn(new byte[32]);
		when(rpc.sendTransaction(any())).thenReturn("Sig");

		assertThat(chain.recordObservation(dealAddress, 3, false)).isEqualTo("Sig");

		ArgumentCaptor<byte[]> tx = ArgumentCaptor.forClass(byte[].class);
		verify(rpc).sendTransaction(tx.capture());
		byte[] expected = DealProgram.observationInstruction(PROGRAM_ID, oracle.address(), dealAddress, 3, false)
			.data();
		byte[] bytes = tx.getValue();
		// The single instruction's data closes the serialized transaction.
		assertThat(Arrays.copyOfRange(bytes, bytes.length - expected.length, bytes.length)).isEqualTo(expected);
	}

	@Test
	void fundsTheOracleOnlyWhenItRunsLow() {
		when(rpc.getBalance(oracle.address())).thenReturn(50L, 500L);
		chain.ensureOracleFunded();
		chain.ensureOracleFunded();
		verify(rpc).requestAirdrop(oracle.address(), 1_000);
	}

	@Test
	void fundingNeverThrowsAndCanBeTurnedOff() {
		when(rpc.getBalance(anyString())).thenThrow(new SolanaRpcException("no faucet"));
		chain.ensureOracleFunded();

		SolanaRpc unused = mock(SolanaRpc.class);
		new SolanaDealChain(unused, oracle, PROGRAM_ID, "http://rpc.test", 0, 1_000).ensureOracleFunded();
		verify(unused, never()).getBalance(anyString());
		verify(unused, never()).requestAirdrop(anyString(), anyLong());
	}

	private static ProgramAccount account(String address, byte[] data) {
		return new ProgramAccount(address, new AccountInfo(PROGRAM_ID, 1, data));
	}

}
