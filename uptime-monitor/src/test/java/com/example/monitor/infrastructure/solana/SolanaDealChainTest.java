package com.example.monitor.infrastructure.solana;

import static com.example.monitor.support.DealFixtures.PROGRAM_ID;
import static com.example.monitor.support.DealFixtures.deal;
import static com.example.monitor.support.DealFixtures.dealCancelledLog;
import static com.example.monitor.support.DealFixtures.dealSettledLog;
import static com.example.monitor.support.DealFixtures.newAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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

import com.example.monitor.domain.deal.DealChain.ChainDeal;
import com.example.monitor.domain.deal.DealChain.Closure;
import com.example.monitor.domain.deal.DealChain.FoundClosure;
import com.example.monitor.domain.deal.DealChain.TxStatus;
import com.example.monitor.infrastructure.solana.SolanaRpc.AccountInfo;
import com.example.monitor.infrastructure.solana.SolanaRpc.SignatureStatus;
import com.example.monitor.infrastructure.solana.SolanaRpc.SolanaRpcException;

class SolanaDealChainTest {

	private final SolanaRpc rpc = mock(SolanaRpc.class);

	private final OracleKey oracle = OracleKey.generate();

	private final SolanaDealChain chain = new SolanaDealChain(rpc, oracle, PROGRAM_ID, "http://rpc.test", 100, 1_000);

	private final String payer = newAddress();

	private final String recipient = newAddress();

	private final String dealAddress = newAddress();

	@Test
	void describesWhereDealsMustBeCreated() {
		assertThat(chain.programId()).isEqualTo(PROGRAM_ID);
		assertThat(chain.oracleAddress()).isEqualTo(oracle.address());
		assertThat(chain.rpcUrl()).isEqualTo("http://rpc.test");
	}

	@Test
	void readsDealAccountsOfTheProgram() {
		byte[] data = deal(payer, recipient, oracle.address(), 1_790_000_000L).window(10, 2)
			.recorded(new int[] { 0 }, true)
			.data();
		when(rpc.getAccountInfo(dealAddress)).thenReturn(new AccountInfo(PROGRAM_ID, 1, data));

		ChainDeal deal = chain.readDeal(dealAddress);

		assertThat(deal.payer()).isEqualTo(payer);
		assertThat(deal.oracle()).isEqualTo(oracle.address());
		assertThat(deal.accepted()).isTrue();
		assertThat(deal.startsAt()).isEqualTo(Instant.ofEpochSecond(1_790_000_000L));
		assertThat(deal.totalRounds()).isEqualTo(5);
		assertThat(deal.upChecks()).isEqualTo(1);
		assertThat(deal.isRecorded(0)).isTrue();
		assertThat(deal.isRecorded(1)).isFalse();
	}

	@Test
	void aMissingAccountIsNullAndAForeignOneIsRejected() {
		assertThat(chain.readDeal(dealAddress)).isNull();

		when(rpc.getAccountInfo(dealAddress)).thenReturn(new AccountInfo(newAddress(), 1, new byte[0]));
		assertThatIllegalArgumentException().isThrownBy(() -> chain.readDeal(dealAddress))
			.withMessageContaining("not owned by the uptime_deal program");
		assertThatIllegalArgumentException().isThrownBy(() -> chain.readDeal("not-base58!"));
	}

	@Test
	void sendsRecordObservationAndSettleDealSignedByTheOracle() {
		when(rpc.getAccountInfo(dealAddress)).thenReturn(
				new AccountInfo(PROGRAM_ID, 1, deal(payer, recipient, oracle.address(), 1_790_000_000L).data()));
		when(rpc.getLatestBlockhash()).thenReturn(new byte[32]);
		when(rpc.sendTransaction(any())).thenReturn("Sig1", "Sig2");
		ChainDeal deal = chain.readDeal(dealAddress);

		assertThat(chain.recordObservation(dealAddress, deal, 3, false)).isEqualTo("Sig1");
		assertThat(chain.sendSettle(dealAddress, deal)).isEqualTo("Sig2");

		ArgumentCaptor<byte[]> txs = ArgumentCaptor.forClass(byte[].class);
		verify(rpc, org.mockito.Mockito.times(2)).sendTransaction(txs.capture());
		// The single instruction's data closes each serialized transaction.
		assertThat(endsWith(txs.getAllValues().get(0),
				DealProgram.observationInstruction(PROGRAM_ID, oracle.address(), dealAddress, 3, false).data()))
			.isTrue();
		assertThat(endsWith(txs.getAllValues().get(1), DealProgram.settleInstruction(PROGRAM_ID, oracle.address(),
				dealAddress, DealProgram.decodeDeal(deal(payer, recipient, oracle.address(), 0).data())).data()))
			.isTrue();
	}

	@Test
	void readsTransactionStatusesAndClosures() {
		when(rpc.getSignatureStatus("Sig")).thenReturn(new SignatureStatus(true, null));
		when(rpc.getTransactionLogs("Sig")).thenReturn(List.of(dealSettledLog(dealAddress, 5, 5, true, 1)));
		when(rpc.getSignaturesForAddress(dealAddress, SolanaDealChain.HISTORY_LIMIT)).thenReturn(List.of("Other", "Sig"));
		when(rpc.getTransactionLogs("Other")).thenReturn(List.of("Program log: hi"));

		assertThat(chain.status("Sig")).isEqualTo(new TxStatus(true, null));
		assertThat(chain.status("Unknown")).isNull();
		assertThat(chain.closureIn("Sig", dealAddress)).isEqualTo(new Closure(false, true, 5L, 5L));
		assertThat(chain.closureIn("Missing", dealAddress)).isNull();
		assertThat(chain.findClosure(dealAddress)).isEqualTo(new FoundClosure("Sig", new Closure(false, true, 5L, 5L)));

		String cancelled = newAddress();
		when(rpc.getSignaturesForAddress(cancelled, SolanaDealChain.HISTORY_LIMIT)).thenReturn(List.of("C"));
		when(rpc.getTransactionLogs("C")).thenReturn(List.of(dealCancelledLog(cancelled, payer, 5)));
		assertThat(chain.findClosure(cancelled).closure().cancelled()).isTrue();
		assertThat(chain.findClosure(newAddress())).isNull();
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

	private static boolean endsWith(byte[] bytes, byte[] suffix) {
		return Arrays.equals(Arrays.copyOfRange(bytes, bytes.length - suffix.length, bytes.length), suffix);
	}

}
