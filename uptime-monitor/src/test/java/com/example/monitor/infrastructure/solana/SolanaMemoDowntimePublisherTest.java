package com.example.monitor.infrastructure.solana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.monitor.domain.DowntimeReport;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.infrastructure.solana.SolanaRpc.SolanaRpcException;

class SolanaMemoDowntimePublisherTest {

	static final ServiceId ID = ServiceId.of("11111111-1111-1111-1111-111111111111");

	static final DowntimeReport REPORT = new DowntimeReport(ID, "Downtime", Duration.ofSeconds(4), 2, 1, true,
			Instant.parse("2026-10-04T12:00:00Z"));

	SolanaRpc rpc = mock(SolanaRpc.class);

	OracleKey oracle = OracleKey.generate();

	byte[] blockhash = new byte[32];

	@Test
	void memoIsCompactJson() {
		assertThat(SolanaMemoDowntimePublisher.memo(REPORT)).isEqualTo(
				"{\"app\":\"uptime-monitor\",\"serviceId\":\"11111111-1111-1111-1111-111111111111\","
						+ "\"trigger\":\"Downtime\",\"totalDowntimeMs\":4000,\"downtimeChecks\":2,"
						+ "\"internalErrors\":1,\"active\":true,\"at\":\"2026-10-04T12:00:00Z\"}");
	}

	@Test
	void sendsAMemoTransactionSignedByTheOracle() {
		Arrays.fill(blockhash, (byte) 7);
		when(rpc.getLatestBlockhash()).thenReturn(blockhash);
		when(rpc.sendTransaction(any())).thenReturn("sig");

		new SolanaMemoDowntimePublisher(rpc, oracle, 0, 0).publish(REPORT);

		ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
		verify(rpc).sendTransaction(sent.capture());
		byte[] tx = sent.getValue();
		assertThat(tx[0]).isEqualTo((byte) 1);
		byte[] signature = Arrays.copyOfRange(tx, 1, 65);
		byte[] message = Arrays.copyOfRange(tx, 65, tx.length);
		assertThat(Ed25519.verify(oracle.publicKey(), message, signature)).isTrue();
		assertThat(message).containsSequence(oracle.publicKey());
		assertThat(message).containsSequence(Base58.decodePublicKey(SolanaMemoDowntimePublisher.MEMO_PROGRAM_ID));
		assertThat(message).containsSequence(blockhash);
		assertThat(message).containsSequence(SolanaMemoDowntimePublisher.memo(REPORT).getBytes(StandardCharsets.UTF_8));
		verify(rpc, never()).getBalance(anyString());
	}

	@Test
	void memoInstructionHasTheOracleAsItsSigner() {
		SolanaTransaction.Instruction ix = new SolanaMemoDowntimePublisher(rpc, oracle, 0, 0).memoInstruction(REPORT);
		assertThat(ix.accounts()).hasSize(1);
		assertThat(ix.accounts().getFirst().publicKey()).isEqualTo(oracle.publicKey());
		assertThat(ix.accounts().getFirst().signer()).isTrue();
	}

	@Test
	void topsUpALowOracleBalanceFirst() {
		when(rpc.getBalance(oracle.address())).thenReturn(10L);
		when(rpc.getLatestBlockhash()).thenReturn(blockhash);

		new SolanaMemoDowntimePublisher(rpc, oracle, 100, 1000).publish(REPORT);

		verify(rpc).requestAirdrop(oracle.address(), 1000);
		verify(rpc).sendTransaction(any());
	}

	@Test
	void fundedOracleIsNotToppedUp() {
		when(rpc.getBalance(oracle.address())).thenReturn(100L);
		when(rpc.getLatestBlockhash()).thenReturn(blockhash);

		new SolanaMemoDowntimePublisher(rpc, oracle, 100, 1000).publish(REPORT);

		verify(rpc, never()).requestAirdrop(anyString(), anyLong());
	}

	@Test
	void failedAirdropStillSends() {
		when(rpc.getBalance(oracle.address())).thenThrow(new SolanaRpcException("no faucet"));
		when(rpc.getLatestBlockhash()).thenReturn(blockhash);

		new SolanaMemoDowntimePublisher(rpc, oracle, 100, 1000).publish(REPORT);

		verify(rpc).sendTransaction(any());
	}

	@Test
	void rpcFailureIsThrown() {
		when(rpc.getLatestBlockhash()).thenReturn(blockhash);
		when(rpc.sendTransaction(any())).thenThrow(new SolanaRpcException("simulation failed"));

		assertThatThrownBy(() -> new SolanaMemoDowntimePublisher(rpc, oracle, 0, 0).publish(REPORT))
			.isInstanceOf(SolanaRpcException.class);
	}

	@Test
	void loggingPublisherNeverFails() {
		new LoggingDowntimePublisher().publish(REPORT);
	}

}
