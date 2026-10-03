package com.example.uptime.deal;

import static com.example.uptime.support.DealFixtures.PROGRAM_ID;
import static com.example.uptime.support.DealFixtures.dealData;
import static com.example.uptime.support.DealFixtures.dealSettledLog;
import static com.example.uptime.support.DealFixtures.newAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.uptime.deal.TrackedDeal.Status;
import com.example.uptime.solana.Ed25519;
import com.example.uptime.solana.OracleKey;
import com.example.uptime.solana.SolanaRpc;
import com.example.uptime.solana.SolanaRpc.AccountInfo;
import com.example.uptime.solana.SolanaRpc.SignatureStatus;
import com.example.uptime.solana.SolanaRpc.SolanaRpcException;
import com.example.uptime.support.MutableClock;
import com.example.uptime.uptime.UptimePoint;
import com.example.uptime.uptime.UptimeQueryService;

class DealServiceTest {

	private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

	private static final long AMOUNT = 500_000_000L;

	private final SolanaRpc rpc = mock(SolanaRpc.class);

	private final UptimeQueryService uptime = mock(UptimeQueryService.class);

	private final OracleKey oracle = OracleKey.generate();

	private final MutableClock clock = new MutableClock(T0.plusMillis(300));

	private final String deal = newAddress();

	private final String payer = newAddress();

	private final String recipient = newAddress();

	private DealService service = service(0);

	private DealService service(long oracleMinLamports) {
		return new DealService(rpc, oracle, uptime,
				new DealProperties("http://rpc", PROGRAM_ID, "", 2, 500, 3600, 3, 30, oracleMinLamports, 1_000), clock);
	}

	@BeforeEach
	void dealExistsOnChain() {
		when(rpc.getAccountInfo(deal)).thenReturn(dealAccount(oracle.address()));
		when(rpc.getLatestBlockhash()).thenReturn(new byte[32]);
		when(rpc.sendTransaction(any())).thenReturn("sig1");
	}

	@Test
	void registerStartsTheWindowAtTheNextWholeSecond() {
		TrackedDeal tracked = service.register(deal, 10);

		assertThat(tracked.status()).isEqualTo(Status.ACTIVE);
		assertThat(tracked.startsAt()).isEqualTo(T0.plusSeconds(1));
		assertThat(tracked.endsAt()).isEqualTo(T0.plusSeconds(11));
		assertThat(tracked.payer()).isEqualTo(payer);
		assertThat(tracked.recipient()).isEqualTo(recipient);
		assertThat(tracked.amountLamports()).isEqualTo(AMOUNT);
		assertThat(service.get(deal)).isEqualTo(tracked);
		assertThat(service.list()).containsExactly(tracked);
		assertThat(service.oracleAddress()).isEqualTo(oracle.address());
	}

	@Test
	void registerRejectsInvalidRequests() {
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(deal, 0));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(deal, 3601));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(null, 10));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register("not base58 0", 10));

		String missing = newAddress();
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(missing, 10))
			.withMessageContaining("No uptime_deal account");

		String foreign = newAddress();
		when(rpc.getAccountInfo(foreign)).thenReturn(new AccountInfo(newAddress(), 1, new byte[0]));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(foreign, 10))
			.withMessageContaining("not owned by the uptime_deal program");

		String otherOracle = newAddress();
		when(rpc.getAccountInfo(otherOracle)).thenReturn(dealAccount(newAddress()));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(otherOracle, 10))
			.withMessageContaining("names oracle");

		assertThat(service.list()).isEmpty();
	}

	@Test
	void registerRejectsDuplicatesAndUnknownLookups() {
		service.register(deal, 10);
		assertThatIllegalStateException().isThrownBy(() -> service.register(deal, 10));
		assertThatThrownBy(() -> service.get(newAddress())).isInstanceOf(NoSuchElementException.class);
	}

	@Test
	void doesNothingUntilTheWindowAndGracePeriodAreOver() {
		service.register(deal, 10);
		clock.set(T0.plusSeconds(12).plusMillis(999));
		service.settleDue();
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void settlesWithMeasuredUptimeAndRecordsTheProgramsVerdict() {
		service.register(deal, 10);
		when(uptime.range(T0.plusSeconds(1), T0.plusSeconds(10))).thenReturn(points(10, 0));
		clock.set(T0.plusSeconds(13));

		service.settleDue();

		ArgumentCaptor<byte[]> tx = ArgumentCaptor.forClass(byte[].class);
		verify(rpc).sendTransaction(tx.capture());
		assertSignedSettlement(tx.getValue(), 10, 10);
		TrackedDeal sent = service.get(deal);
		assertThat(sent.status()).isEqualTo(Status.ACTIVE);
		assertThat(sent.signature()).isEqualTo("sig1");
		assertThat(sent.upSeconds()).isEqualTo(10);
		assertThat(sent.totalSeconds()).isEqualTo(10);

		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(false, null));
		service.settleDue();
		assertThat(service.get(deal).status()).isEqualTo(Status.ACTIVE);

		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, null));
		when(rpc.getTransactionLogs("sig1")).thenReturn(List.of(dealSettledLog(deal, 10, 10, true, AMOUNT)));
		service.settleDue();

		TrackedDeal settled = service.get(deal);
		assertThat(settled.status()).isEqualTo(Status.SETTLED);
		assertThat(settled.paidToRecipient()).isTrue();
		verify(rpc, times(1)).sendTransaction(any());

		service.settleDue();
		verify(rpc, times(2)).getSignatureStatus("sig1");
	}

	@Test
	void reportsDowntimeAndARefundVerdict() {
		service.register(deal, 10);
		when(uptime.range(any(), any())).thenReturn(points(7, 3));
		clock.set(T0.plusSeconds(13));
		service.settleDue();

		ArgumentCaptor<byte[]> tx = ArgumentCaptor.forClass(byte[].class);
		verify(rpc).sendTransaction(tx.capture());
		assertSignedSettlement(tx.getValue(), 7, 10);

		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, null));
		when(rpc.getTransactionLogs("sig1")).thenReturn(List.of(dealSettledLog(deal, 7, 10, false, AMOUNT)));
		service.settleDue();
		assertThat(service.get(deal).paidToRecipient()).isFalse();
		assertThat(service.get(deal).status()).isEqualTo(Status.SETTLED);
	}

	@Test
	void missingLogsStillSettleWithAnUnknownVerdict() {
		service.register(deal, 1);
		when(uptime.range(any(), any())).thenReturn(points(1, 0));
		clock.set(T0.plusSeconds(4));
		service.settleDue();
		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, null));
		service.settleDue();
		assertThat(service.get(deal).status()).isEqualTo(Status.SETTLED);
		assertThat(service.get(deal).paidToRecipient()).isNull();
	}

	@Test
	void retriesFailedSendsThenGivesUp() {
		service.register(deal, 10);
		when(uptime.range(any(), any())).thenReturn(points(10, 0));
		when(rpc.sendTransaction(any())).thenThrow(new SolanaRpcException("insufficient funds"));
		clock.set(T0.plusSeconds(13));

		service.settleDue();
		service.settleDue();
		assertThat(service.get(deal).status()).isEqualTo(Status.ACTIVE);
		assertThat(service.get(deal).attempts()).isEqualTo(2);
		assertThat(service.get(deal).error()).isEqualTo("insufficient funds");

		service.settleDue();
		assertThat(service.get(deal).status()).isEqualTo(Status.FAILED);
		service.settleDue();
		verify(rpc, times(3)).sendTransaction(any());
	}

	@Test
	void failedTransactionsFailTheDeal() {
		service.register(deal, 10);
		when(uptime.range(any(), any())).thenReturn(points(10, 0));
		clock.set(T0.plusSeconds(13));
		service.settleDue();
		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, "{InstructionError=[0, Custom(6002)]}"));
		service.settleDue();

		assertThat(service.get(deal).status()).isEqualTo(Status.FAILED);
		assertThat(service.get(deal).error()).contains("Custom(6002)");
	}

	@Test
	void resendsWhenASettlementIsNotConfirmedInTime() {
		service.register(deal, 10);
		when(uptime.range(any(), any())).thenReturn(points(10, 0));
		clock.set(T0.plusSeconds(13));
		service.settleDue();

		clock.set(T0.plusSeconds(44));
		service.settleDue();
		assertThat(service.get(deal).signature()).isNull();
		assertThat(service.get(deal).attempts()).isEqualTo(1);

		service.settleDue();
		verify(rpc, times(2)).sendTransaction(any());
	}

	@Test
	void aDealThatVanishedAfterASendCountsAsSettled() {
		service.register(deal, 10);
		when(uptime.range(any(), any())).thenReturn(points(10, 0));
		clock.set(T0.plusSeconds(13));
		service.settleDue();
		clock.set(T0.plusSeconds(44));
		service.settleDue();

		when(rpc.getAccountInfo(deal)).thenReturn(null);
		service.settleDue();
		assertThat(service.get(deal).status()).isEqualTo(Status.SETTLED);
	}

	@Test
	void aDealClosedBeforeAnySendFails() {
		service.register(deal, 10);
		when(rpc.getAccountInfo(deal)).thenReturn(null);
		clock.set(T0.plusSeconds(13));
		service.settleDue();
		assertThat(service.get(deal).status()).isEqualTo(Status.FAILED);
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void oneFailingDealDoesNotBlockOthers() {
		String other = newAddress();
		when(rpc.getAccountInfo(other)).thenReturn(dealAccount(oracle.address()));
		service.register(deal, 10);
		service.register(other, 10);
		when(uptime.range(any(), any())).thenThrow(new IllegalStateException("db down")).thenReturn(points(10, 0));
		clock.set(T0.plusSeconds(13));

		service.settleDue();

		List<TrackedDeal> deals = new ArrayList<>(service.list());
		assertThat(deals).extracting(TrackedDeal::attempts).containsExactlyInAnyOrder(0, 1);
		assertThat(deals).extracting(TrackedDeal::signature).containsExactlyInAnyOrder("sig1", null);
	}

	@Test
	void fundsTheOracleFromTheFaucetWhenItIsLow() {
		service = service(100);
		when(rpc.getBalance(oracle.address())).thenReturn(99L);
		service.register(deal, 10);
		verify(rpc).requestAirdrop(oracle.address(), 1_000);

		String other = newAddress();
		when(rpc.getAccountInfo(other)).thenReturn(dealAccount(oracle.address()));
		when(rpc.getBalance(oracle.address())).thenReturn(100L);
		service.register(other, 10);
		verify(rpc, times(1)).requestAirdrop(anyString(), anyLong());
	}

	@Test
	void aFaucetFailureDoesNotBlockRegistration() {
		service = service(100);
		when(rpc.getBalance(oracle.address())).thenThrow(new SolanaRpcException("no faucet"));
		assertThat(service.register(deal, 10).status()).isEqualTo(Status.ACTIVE);
	}

	@Test
	void noFundingWhenDisabled() {
		service.register(deal, 10);
		verify(rpc, never()).getBalance(anyString());
	}

	private AccountInfo dealAccount(String dealOracle) {
		return new AccountInfo(PROGRAM_ID, AMOUNT + 2_000_000, dealData(payer, recipient, dealOracle, 1, AMOUNT));
	}

	private static List<UptimePoint> points(int up, int down) {
		List<UptimePoint> points = new ArrayList<>();
		for (int i = 0; i < up + down; i++) {
			points.add(new UptimePoint(T0.plusSeconds(i), i >= up));
		}
		return points;
	}

	/** Checks the oracle's signature and the up/total encoded at the end of the settle_deal data. */
	private void assertSignedSettlement(byte[] tx, long up, long total) {
		byte[] message = Arrays.copyOfRange(tx, 65, tx.length);
		assertThat(Ed25519.verify(oracle.publicKey(), message, Arrays.copyOfRange(tx, 1, 65))).isTrue();
		ByteBuffer data = ByteBuffer.wrap(message, message.length - 16, 16).order(ByteOrder.LITTLE_ENDIAN);
		assertThat(data.getLong()).isEqualTo(up);
		assertThat(data.getLong()).isEqualTo(total);
	}

}
