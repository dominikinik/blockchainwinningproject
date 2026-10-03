package com.example.uptime.deal;

import static com.example.uptime.support.DealFixtures.PROGRAM_ID;
import static com.example.uptime.support.DealFixtures.deal;
import static com.example.uptime.support.DealFixtures.dealCancelledLog;
import static com.example.uptime.support.DealFixtures.dealSettledLog;
import static com.example.uptime.support.DealFixtures.newAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
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
import com.example.uptime.solana.SolanaRpc.ProgramAccount;
import com.example.uptime.solana.SolanaRpc.SignatureStatus;
import com.example.uptime.solana.SolanaRpc.SolanaRpcException;
import com.example.uptime.state.ApplicationStateHealthIndicator;
import com.example.uptime.state.ApplicationStateService;
import com.example.uptime.support.DealFixtures;
import com.example.uptime.support.MutableClock;
import com.example.uptime.uptime.UptimeQueryService;
import com.example.uptime.uptime.UptimeRecordRepository;

/**
 * The deal monitor against a mocked chain. The default deal is active with 10 one-second rounds from
 * {@code T0 + 1}, so its window ends at {@code T0 + 11}, the program opens settlement at {@code T0 + 21},
 * and the service settles from {@code T0 + 23}.
 */
class DealServiceTest {

	private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

	private static final Instant START = T0.plusSeconds(1);

	private static final Instant SETTLE = T0.plusSeconds(23);

	private static final long AMOUNT = 500_000_000L;

	private static final byte[] SETTLE_DISCRIMINATOR = { 28, 10, (byte) 168, (byte) 174, (byte) 203, (byte) 149,
			(byte) 134, 54 };

	private final SolanaRpc rpc = mock(SolanaRpc.class);

	private final ApplicationStateService state = new ApplicationStateService();

	private final OracleKey oracle = OracleKey.generate();

	private final MutableClock clock = new MutableClock(T0.plusMillis(300));

	private final String deal = newAddress();

	private final String payer = newAddress();

	private final String recipient = newAddress();

	private DealService service = service(0);

	private DealService service(long oracleMinLamports) {
		return new DealService(rpc, oracle, new ApplicationStateHealthIndicator(state),
				new DealProperties("http://rpc", PROGRAM_ID, "", 2, 500, 3600, 3, 30, oracleMinLamports, 1_000, 10_000,
						200, 2_000, 15_000),
				clock);
	}

	@BeforeEach
	void dealExistsOnChain() {
		onChain(deal, fixture());
		when(rpc.getLatestBlockhash()).thenReturn(new byte[32]);
		when(rpc.sendTransaction(any())).thenReturn("sig1");
	}

	// --- registration and discovery ---------------------------------------------------------------

	@Test
	void registerTakesTermsAndWindowFromTheDealAccount() {
		TrackedDeal tracked = service.register(deal);

		assertThat(tracked.status()).isEqualTo(Status.ACTIVE);
		assertThat(tracked.startsAt()).isEqualTo(START);
		assertThat(tracked.endsAt()).isEqualTo(T0.plusSeconds(11));
		assertThat(tracked.payer()).isEqualTo(payer);
		assertThat(tracked.recipient()).isEqualTo(recipient);
		assertThat(tracked.amountLamports()).isEqualTo(AMOUNT);
		assertThat(tracked.totalRounds()).isEqualTo(10);
		assertThat(tracked.checkIntervalSeconds()).isEqualTo(1);
		assertThat(tracked.minUptimeBps()).isEqualTo(9_000);
		assertThat(service.get(deal)).isEqualTo(tracked);
		assertThat(service.list()).containsExactly(tracked);
		assertThat(service.oracleAddress()).isEqualTo(oracle.address());
	}

	@Test
	void registerRejectsInvalidRequests() {
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(null));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(" "));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register("not base58 0"));

		String missing = newAddress();
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(missing))
			.withMessageContaining("No uptime_deal account");

		String foreign = newAddress();
		when(rpc.getAccountInfo(foreign)).thenReturn(new AccountInfo(newAddress(), 1, new byte[0]));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(foreign))
			.withMessageContaining("not owned by the uptime_deal program");

		String otherOracle = newAddress();
		onChain(otherOracle, deal(payer, recipient, newAddress(), START.getEpochSecond()));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(otherOracle))
			.withMessageContaining("names oracle");

		assertThat(service.list()).isEmpty();
	}

	@Test
	void registerRejectsOutOfRangeOnChainDurations() {
		String zero = newAddress();
		onChain(zero, fixture().window(0, 1));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(zero))
			.withMessageContaining("between 1 and 3600");

		String tooLong = newAddress();
		onChain(tooLong, fixture().window(3601, 1));
		assertThatIllegalArgumentException().isThrownBy(() -> service.register(tooLong));

		String longest = newAddress();
		onChain(longest, fixture().window(3600, 60));
		assertThat(service.register(longest).durationSeconds()).isEqualTo(3600);
		assertThat(service.list()).hasSize(1);
	}

	@Test
	void registerRejectsDuplicatesAndUnknownLookups() {
		service.register(deal);
		assertThatThrownBy(() -> service.register(deal)).isInstanceOf(DealAlreadyRegisteredException.class);
		assertThatThrownBy(() -> service.get(newAddress())).isInstanceOf(NoSuchElementException.class);
	}

	@Test
	void discoverTracksOpenDealsThatNameThisOracle() {
		String other = newAddress();
		String foreign = newAddress();
		String garbage = newAddress();
		when(rpc.getProgramAccounts(PROGRAM_ID, DealProgram.ORACLE_OFFSET, oracle.address())).thenReturn(List.of(
				programAccount(deal, fixture()), programAccount(other, fixture().awaitingProvider(5)),
				programAccount(foreign, deal(payer, recipient, newAddress(), 0)),
				new ProgramAccount(garbage, new AccountInfo(PROGRAM_ID, 1, new byte[] { 1, 2, 3 }))));

		service.discover();
		service.discover();

		assertThat(service.list()).extracting(TrackedDeal::address).containsExactlyInAnyOrder(deal, other);
		assertThat(service.get(other).status()).isEqualTo(Status.AWAITING_PROVIDER);
		assertThatThrownBy(() -> service.register(deal)).isInstanceOf(DealAlreadyRegisteredException.class);
	}

	@Test
	void aDiscoveryFailureIsLoggedAndRetried() {
		when(rpc.getProgramAccounts(anyString(), anyInt(), anyString()))
			.thenThrow(new SolanaRpcException("down"))
			.thenReturn(List.of(programAccount(deal, fixture())));
		service.discover();
		assertThat(service.list()).isEmpty();
		service.discover();
		assertThat(service.list()).hasSize(1);
	}

	// --- observations -------------------------------------------------------------------------------

	@Test
	void upAndDownRoundsAreReportedOnceEachRoundHasEnded() {
		service.register(deal);
		sampleAt(START.plusMillis(100)); // round 0 UP
		sampleAt(START.plusMillis(900));
		sampleAt(START.plusMillis(1_100)); // round 1: UP, then DOWN
		state.stop();
		sampleAt(START.plusMillis(1_500));
		state.start();
		sampleAt(START.plusMillis(1_900));

		advanceAt(START.plusMillis(1_950)); // round 0 ended, round 1 still running
		advanceAt(START.plusMillis(2_100)); // round 1 ended; round 0 isn't due again yet
		assertThat(sentObservations()).containsExactly(new Observation(0, true), new Observation(1, false));
		assertThat(service.get(deal).observationsSent()).isEqualTo(2);
	}

	@Test
	void unrecordedRoundsAreResentAndRecordedOnesForgotten() {
		service.register(deal);
		sampleAt(START.plusMillis(100));
		sampleAt(START.plusMillis(1_100));
		advanceAt(START.plusMillis(2_000)); // sends rounds 0 and 1

		onChain(deal, fixture().recorded(new int[] { 0 }, true));
		advanceAt(START.plusMillis(3_999)); // retry not due yet
		advanceAt(START.plusMillis(4_000)); // round 1 resent, round 0 is on chain

		assertThat(sentObservations()).containsExactly(new Observation(0, true), new Observation(1, true),
				new Observation(1, true));
		assertThat(service.get(deal).upChecks()).isEqualTo(1);
	}

	@Test
	void aFailedObservationSendIsRetried() {
		when(rpc.sendTransaction(any())).thenThrow(new SolanaRpcException("RoundNotEnded")).thenReturn("sig1");
		service.register(deal);
		sampleAt(START.plusMillis(100));
		advanceAt(START.plusMillis(1_000));
		assertThat(service.get(deal).observationsSent()).isZero();
		assertThat(service.get(deal).attempts()).isZero();

		advanceAt(START.plusMillis(3_000));
		assertThat(sentObservations()).containsExactly(new Observation(0, true), new Observation(0, true));
		assertThat(service.get(deal).observationsSent()).isEqualTo(1);
	}

	@Test
	void roundsTheServiceDidNotWitnessAreNeverReported() {
		service.register(deal);
		// Down (or not tracking) for rounds 0-3, then back for round 4.
		sampleAt(START.plusMillis(4_500));
		advanceAt(START.plusMillis(6_000));
		assertThat(sentObservations()).containsExactly(new Observation(4, true));
	}

	@Test
	void observeIgnoresDealsOutsideTheirWindowAndDealsAwaitingTheProvider() {
		String pending = newAddress();
		onChain(pending, fixture().awaitingProvider(5));
		service.register(pending);
		service.register(deal);

		sampleAt(START.minusMillis(1)); // before the window
		sampleAt(T0.plusSeconds(11)); // window over
		advanceAt(T0.plusSeconds(20));
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void noObservationsAreSentOnceTheProgramClosedThem() {
		service.register(deal);
		sampleAt(START.plusMillis(9_500));
		advanceAt(T0.plusSeconds(21)); // settlement open on chain, our extra grace not over
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void aDealAwaitingTheProviderStartsWhenTheProviderAccepts() {
		onChain(deal, fixture().awaitingProvider(5));
		TrackedDeal pending = service.register(deal);
		assertThat(pending.status()).isEqualTo(Status.AWAITING_PROVIDER);
		assertThat(pending.startsAt()).isNull();
		assertThat(pending.providerStakeLamports()).isEqualTo(5);
		advanceAt(T0.plusSeconds(100));
		assertThat(service.get(deal).status()).isEqualTo(Status.AWAITING_PROVIDER);

		onChain(deal, deal(payer, recipient, oracle.address(), 200).amount(AMOUNT));
		advanceAt(T0.plusSeconds(101));
		TrackedDeal active = service.get(deal);
		assertThat(active.status()).isEqualTo(Status.ACTIVE);
		assertThat(active.startsAt()).isEqualTo(Instant.ofEpochSecond(200));
		assertThat(active.endsAt()).isEqualTo(Instant.ofEpochSecond(210));
	}

	// --- settlement ---------------------------------------------------------------------------------

	@Test
	void settlementWaitsForTheProgramsGraceAndOurs() {
		service.register(deal);
		advanceAt(SETTLE.minusMillis(1));
		verify(rpc, never()).sendTransaction(any());
		advanceAt(SETTLE);
		verify(rpc).sendTransaction(any());
	}

	@Test
	void settleDealCarriesNoUptimeFiguresAndTheProgramsVerdictIsRecorded() {
		onChain(deal, fixture().recorded(new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 }, true));
		service.register(deal);
		advanceAt(SETTLE);

		byte[] message = signedMessage(lastSentTransaction());
		assertThat(Arrays.copyOfRange(message, message.length - 9, message.length))
			.as("data length 8, then only the settle_deal discriminator")
			.containsExactly(concat(new byte[] { 8 }, SETTLE_DISCRIMINATOR));
		TrackedDeal sent = service.get(deal);
		assertThat(sent.status()).isEqualTo(Status.ACTIVE);
		assertThat(sent.signature()).isEqualTo("sig1");
		assertThat(sent.upChecks()).isEqualTo(9);

		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(false, null));
		advanceAt(SETTLE.plusSeconds(1));
		assertThat(service.get(deal).status()).isEqualTo(Status.ACTIVE);

		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, null));
		when(rpc.getTransactionLogs("sig1")).thenReturn(List.of(dealSettledLog(deal, 9, 0, 10, true, AMOUNT)));
		advanceAt(SETTLE.plusSeconds(2));

		TrackedDeal settled = service.get(deal);
		assertThat(settled.status()).isEqualTo(Status.SETTLED);
		assertThat(settled.paidToRecipient()).isTrue();
		assertThat(settled.upChecks()).isEqualTo(9);
		verify(rpc, times(1)).sendTransaction(any());

		advanceAt(SETTLE.plusSeconds(3));
		verify(rpc, times(2)).getSignatureStatus("sig1");
	}

	@Test
	void aBreachVerdictIsRecorded() {
		service.register(deal);
		advanceAt(SETTLE);
		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, null));
		when(rpc.getTransactionLogs("sig1")).thenReturn(List.of(dealSettledLog(deal, 7, 3, 10, false, AMOUNT)));
		advanceAt(SETTLE.plusSeconds(1));
		assertThat(service.get(deal).paidToRecipient()).isFalse();
		assertThat(service.get(deal).downChecks()).isEqualTo(3);
		assertThat(service.get(deal).status()).isEqualTo(Status.SETTLED);
	}

	@Test
	void missingLogsStillSettleWithAnUnknownVerdict() {
		service.register(deal);
		advanceAt(SETTLE);
		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, null));
		advanceAt(SETTLE.plusSeconds(1));
		assertThat(service.get(deal).status()).isEqualTo(Status.SETTLED);
		assertThat(service.get(deal).paidToRecipient()).isNull();
	}

	@Test
	void retriesFailedSettlementSendsThenGivesUp() {
		service.register(deal);
		when(rpc.sendTransaction(any())).thenThrow(new SolanaRpcException("SettleTooEarly"));

		advanceAt(SETTLE);
		advanceAt(SETTLE);
		assertThat(service.get(deal).status()).isEqualTo(Status.ACTIVE);
		assertThat(service.get(deal).attempts()).isEqualTo(2);
		assertThat(service.get(deal).error()).isEqualTo("SettleTooEarly");

		advanceAt(SETTLE);
		assertThat(service.get(deal).status()).isEqualTo(Status.FAILED);
		advanceAt(SETTLE);
		verify(rpc, times(3)).sendTransaction(any());
	}

	@Test
	void failedTransactionsFailTheDeal() {
		service.register(deal);
		advanceAt(SETTLE);
		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, "{InstructionError=[0, Custom(6014)]}"));
		advanceAt(SETTLE.plusSeconds(1));

		assertThat(service.get(deal).status()).isEqualTo(Status.FAILED);
		assertThat(service.get(deal).error()).contains("Custom(6014)");
	}

	@Test
	void resendsWhenASettlementIsNotConfirmedInTime() {
		service.register(deal);
		advanceAt(SETTLE);

		advanceAt(SETTLE.plusSeconds(31));
		assertThat(service.get(deal).signature()).isNull();
		assertThat(service.get(deal).attempts()).isEqualTo(1);

		advanceAt(SETTLE.plusSeconds(31));
		verify(rpc, times(2)).sendTransaction(any());
	}

	@Test
	void aDealSettledBySomeoneElseIsReadFromHistory() {
		service.register(deal);
		when(rpc.getAccountInfo(deal)).thenReturn(null);
		when(rpc.getSignaturesForAddress(deal, 10)).thenReturn(List.of("newer", "landed"));
		when(rpc.getTransactionLogs("newer")).thenReturn(List.of("Program log: unrelated"));
		when(rpc.getTransactionLogs("landed")).thenReturn(List.of(dealSettledLog(deal, 10, 0, 10, true, AMOUNT)));
		advanceAt(T0.plusSeconds(22)); // e.g. the customer settled it before this service did

		TrackedDeal settled = service.get(deal);
		assertThat(settled.status()).isEqualTo(Status.SETTLED);
		assertThat(settled.paidToRecipient()).isTrue();
		assertThat(settled.signature()).isEqualTo("landed");
		assertThat(settled.upChecks()).isEqualTo(10);
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void aDealCancelledByItsPayerIsMarkedCancelled() {
		onChain(deal, fixture().awaitingProvider(5));
		service.register(deal);
		when(rpc.getAccountInfo(deal)).thenReturn(null);
		when(rpc.getSignaturesForAddress(deal, 10)).thenReturn(List.of("cancelTx"));
		when(rpc.getTransactionLogs("cancelTx")).thenReturn(List.of(dealCancelledLog(deal, payer, AMOUNT)));
		advanceAt(T0.plusSeconds(5));

		assertThat(service.get(deal).status()).isEqualTo(Status.CANCELLED);
		assertThat(service.get(deal).signature()).isEqualTo("cancelTx");
		advanceAt(T0.plusSeconds(6));
		verify(rpc, times(1)).getSignaturesForAddress(deal, 10);
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void aVanishedDealWithoutAClosingEventFails() {
		service.register(deal);
		when(rpc.getAccountInfo(deal)).thenReturn(null);
		when(rpc.getSignaturesForAddress(deal, 10)).thenReturn(List.of("a", "b"));
		when(rpc.getTransactionLogs("a")).thenReturn(List.of("Program log: nothing"));
		when(rpc.getTransactionLogs("b")).thenReturn(null);
		advanceAt(SETTLE);

		assertThat(service.get(deal).status()).isEqualTo(Status.FAILED);
		assertThat(service.get(deal).error()).contains("no DealSettled or DealCancelled event");
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void eventsOfOtherDealsAreIgnoredWhenAVanishedDealIsExplained() {
		service.register(deal);
		String other = newAddress();
		when(rpc.getAccountInfo(deal)).thenReturn(null);
		when(rpc.getSignaturesForAddress(deal, 10)).thenReturn(List.of("x", "y"));
		when(rpc.getTransactionLogs("x")).thenReturn(List.of(dealSettledLog(other, 10, 0, 10, true, AMOUNT)));
		when(rpc.getTransactionLogs("y")).thenReturn(List.of(dealCancelledLog(other, payer, AMOUNT)));
		advanceAt(SETTLE);

		assertThat(service.get(deal).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void rpcFailuresCountAsSettlementAttemptsOnlyOnceSettlementIsDue() {
		service.register(deal);
		when(rpc.getAccountInfo(deal)).thenThrow(new SolanaRpcException("down"));
		advanceAt(T0.plusSeconds(5));
		assertThat(service.get(deal).status()).isEqualTo(Status.ACTIVE);
		assertThat(service.get(deal).attempts()).isZero();

		advanceAt(SETTLE);
		assertThat(service.get(deal).status()).isEqualTo(Status.ACTIVE);
		assertThat(service.get(deal).attempts()).isEqualTo(1);
	}

	@Test
	void oneFailingDealDoesNotBlockOthers() {
		String other = newAddress();
		onChain(other, fixture());
		service.register(deal);
		service.register(other);
		when(rpc.getAccountInfo(deal)).thenThrow(new IllegalStateException("boom"));

		advanceAt(SETTLE);

		assertThat(service.get(deal).attempts()).isEqualTo(1);
		assertThat(service.get(other).signature()).isEqualTo("sig1");
	}

	// --- independence from the uptime database ----------------------------------------------------

	@Test
	void theMonitorHasNoAccessToTheUptimeDatabase() {
		for (Constructor<?> constructor : DealService.class.getConstructors()) {
			assertThat(constructor.getParameterTypes()).doesNotContain(UptimeQueryService.class,
					UptimeRecordRepository.class);
		}
	}

	@Test
	void aRestartedServiceWithNoHistorySettlesFromTheOnChainCounters() {
		onChain(deal, fixture().recorded(new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 }, true));
		when(rpc.getProgramAccounts(PROGRAM_ID, DealProgram.ORACLE_OFFSET, oracle.address()))
			.thenReturn(List.of(programAccount(deal, fixture().recorded(new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 }, true))));

		// A fresh instance: nothing witnessed, nothing registered, no database.
		DealService restarted = service(0);
		clock.set(SETTLE);
		restarted.discover();
		restarted.advance();

		byte[] message = signedMessage(lastSentTransaction());
		assertThat(Arrays.copyOfRange(message, message.length - 8, message.length)).containsExactly(SETTLE_DISCRIMINATOR);
		assertThat(restarted.get(deal).upChecks()).isEqualTo(10);
	}

	// --- oracle funding -----------------------------------------------------------------------------

	@Test
	void fundsTheOracleFromTheFaucetWhenItIsLow() {
		service = service(100);
		when(rpc.getBalance(oracle.address())).thenReturn(99L);
		service.register(deal);
		verify(rpc).requestAirdrop(oracle.address(), 1_000);

		String other = newAddress();
		onChain(other, fixture());
		when(rpc.getBalance(oracle.address())).thenReturn(100L);
		service.register(other);
		verify(rpc, times(1)).requestAirdrop(anyString(), anyLong());
	}

	@Test
	void aFaucetFailureDoesNotBlockRegistration() {
		service = service(100);
		when(rpc.getBalance(oracle.address())).thenThrow(new SolanaRpcException("no faucet"));
		assertThat(service.register(deal).status()).isEqualTo(Status.ACTIVE);
	}

	@Test
	void noFundingWhenDisabled() {
		service.register(deal);
		verify(rpc, never()).getBalance(anyString());
	}

	// --- helpers ------------------------------------------------------------------------------------

	private record Observation(int round, boolean up) {
	}

	private DealFixtures.Deal fixture() {
		return deal(payer, recipient, oracle.address(), START.getEpochSecond()).amount(AMOUNT);
	}

	private void onChain(String address, DealFixtures.Deal fixture) {
		when(rpc.getAccountInfo(address)).thenReturn(new AccountInfo(PROGRAM_ID, AMOUNT + 2_000_000, fixture.data()));
	}

	private static ProgramAccount programAccount(String address, DealFixtures.Deal fixture) {
		return new ProgramAccount(address, new AccountInfo(PROGRAM_ID, 1, fixture.data()));
	}

	private void sampleAt(Instant at) {
		clock.set(at);
		service.observe();
	}

	private void advanceAt(Instant at) {
		clock.set(at);
		service.advance();
	}

	private byte[] lastSentTransaction() {
		ArgumentCaptor<byte[]> tx = ArgumentCaptor.forClass(byte[].class);
		verify(rpc, org.mockito.Mockito.atLeastOnce()).sendTransaction(tx.capture());
		List<byte[]> all = tx.getAllValues();
		return all.get(all.size() - 1);
	}

	/** Verifies the oracle's signature and returns the signed message. */
	private byte[] signedMessage(byte[] tx) {
		byte[] message = Arrays.copyOfRange(tx, 65, tx.length);
		assertThat(Ed25519.verify(oracle.publicKey(), message, Arrays.copyOfRange(tx, 1, 65))).isTrue();
		return message;
	}

	/** Decodes the round and result at the end of every {@code record_observation} sent so far. */
	private List<Observation> sentObservations() {
		ArgumentCaptor<byte[]> tx = ArgumentCaptor.forClass(byte[].class);
		verify(rpc, org.mockito.Mockito.atLeast(0)).sendTransaction(tx.capture());
		List<Observation> observations = tx.getAllValues().stream().map(this::signedMessage).map(message -> {
			ByteBuffer data = ByteBuffer.wrap(message, message.length - 5, 5).order(ByteOrder.LITTLE_ENDIAN);
			return new Observation(data.getInt(), data.get() == 1);
		}).toList();
		clearInvocations(rpc);
		return observations;
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

}
