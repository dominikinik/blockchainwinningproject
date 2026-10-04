package com.example.monitor.application;

import static com.example.monitor.support.DealFixtures.GUARANTEE;
import static com.example.monitor.support.DealFixtures.PROGRAM_ID;
import static com.example.monitor.support.DealFixtures.dealCancelledLog;
import static com.example.monitor.support.DealFixtures.dealData;
import static com.example.monitor.support.DealFixtures.dealSettledLog;
import static com.example.monitor.support.DealFixtures.newAddress;
import static com.example.monitor.support.DealFixtures.proposalData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.InternalErrorHappened;
import com.example.monitor.domain.TrackingEvent.TrackingFinished;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;
import com.example.monitor.domain.deal.DealAlreadyRegisteredException;
import com.example.monitor.domain.deal.UptimeDeal;
import com.example.monitor.domain.deal.UptimeDeal.Status;
import com.example.monitor.infrastructure.solana.Ed25519;
import com.example.monitor.infrastructure.solana.OracleKey;
import com.example.monitor.infrastructure.solana.SolanaDealChain;
import com.example.monitor.infrastructure.solana.SolanaRpc;
import com.example.monitor.infrastructure.solana.SolanaRpc.AccountInfo;
import com.example.monitor.infrastructure.solana.SolanaRpc.SignatureStatus;
import com.example.monitor.infrastructure.solana.SolanaRpc.SolanaRpcException;
import com.example.monitor.support.InMemoryTrackingEventStore;
import com.example.monitor.support.InMemoryUptimeDealRepository;
import com.example.monitor.support.MutableClock;

/**
 * The oracle end to end below the HTTP layer: {@link DealService} with the real {@link SolanaDealChain} over a
 * mocked {@link SolanaRpc}, so the signed settle_deal bytes are checked too. The deal window is
 * {@code [T0+1, T0+11)}; the service has been tracked since {@code T0-100} with a 2 s interval.
 */
class DealServiceTest {

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

	static final long AMOUNT = 500_000_000L;

	final SolanaRpc rpc = mock(SolanaRpc.class);

	final OracleKey oracle = OracleKey.generate();

	final MutableClock clock = new MutableClock(T0.plusMillis(300));

	final InMemoryTrackingEventStore events = new InMemoryTrackingEventStore();

	final InMemoryUptimeDealRepository repo = new InMemoryUptimeDealRepository();

	final ServiceId service = ServiceId.newId();

	final String deal = newAddress();

	final String payer = newAddress();

	final String recipient = newAddress();

	DealService deals = deals(0, service);

	DealService deals(long oracleMinLamports, ServiceId defaultService) {
		return new DealService(repo, new SolanaDealChain(rpc, oracle, PROGRAM_ID, "http://rpc", oracleMinLamports, 1_000),
				events, clock, new DealService.Settings(defaultService, 3600, 2, 3, 30));
	}

	@BeforeEach
	void setUp() {
		append(new TrackingStarted(service, "http://p/health", Duration.ofSeconds(2), T0.minusSeconds(100)));
		when(rpc.getAccountInfo(deal)).thenReturn(dealAccount(oracle.address()));
		when(rpc.getLatestBlockhash()).thenReturn(new byte[32]);
		when(rpc.sendTransaction(any())).thenReturn("sig1");
	}

	@Test
	void registerTakesTheWindowFromTheDealAccountAndLinksTheDefaultService() {
		UptimeDeal tracked = deals.register(deal, null);

		assertThat(tracked.status()).isEqualTo(Status.ACTIVE);
		assertThat(tracked.serviceId()).isEqualTo(service);
		assertThat(tracked.startsAt()).isEqualTo(T0.plusSeconds(1));
		assertThat(tracked.endsAt()).isEqualTo(T0.plusSeconds(11));
		assertThat(tracked.payer()).isEqualTo(payer);
		assertThat(tracked.recipient()).isEqualTo(recipient);
		assertThat(tracked.amountLamports()).isEqualTo(AMOUNT);
		assertThat(tracked.guaranteeLamports()).isEqualTo(GUARANTEE);
		assertThat(deals.get(deal)).isEqualTo(tracked);
		assertThat(deals.list()).containsExactly(tracked);
		assertThat(deals.config()).isEqualTo(new DealService.Config(PROGRAM_ID, oracle.address(), "http://rpc"));
	}

	@Test
	void registerRejectsInvalidRequests() {
		assertThatIllegalArgumentException().isThrownBy(() -> deals.register(null, null));
		assertThatIllegalArgumentException().isThrownBy(() -> deals.register("not base58 0", null));
		assertThatIllegalArgumentException().isThrownBy(() -> deals.register(deal, ServiceId.newId()))
			.withMessageContaining("is not tracked");
		assertThatIllegalArgumentException().isThrownBy(() -> deals(0, null).register(deal, null))
			.withMessageContaining("serviceId is required");

		String missing = newAddress();
		assertThatIllegalArgumentException().isThrownBy(() -> deals.register(missing, null))
			.withMessageContaining("No uptime_deal account");

		String foreign = newAddress();
		when(rpc.getAccountInfo(foreign)).thenReturn(new AccountInfo(newAddress(), 1, new byte[0]));
		assertThatIllegalArgumentException().isThrownBy(() -> deals.register(foreign, null))
			.withMessageContaining("not owned by the uptime_deal program");

		String otherOracle = newAddress();
		when(rpc.getAccountInfo(otherOracle)).thenReturn(dealAccount(newAddress()));
		assertThatIllegalArgumentException().isThrownBy(() -> deals.register(otherOracle, null))
			.withMessageContaining("names oracle");

		assertThat(deals.list()).isEmpty();
	}

	@Test
	void registerRejectsOutOfRangeOnChainDurations() {
		String zero = newAddress();
		when(rpc.getAccountInfo(zero)).thenReturn(dealAccount(oracle.address(), T0, 0));
		assertThatIllegalArgumentException().isThrownBy(() -> deals.register(zero, null))
			.withMessageContaining("between 1 and 3600");
		String tooLong = newAddress();
		when(rpc.getAccountInfo(tooLong)).thenReturn(dealAccount(oracle.address(), T0, 3601));
		assertThatIllegalArgumentException().isThrownBy(() -> deals.register(tooLong, null));
		String longest = newAddress();
		when(rpc.getAccountInfo(longest)).thenReturn(dealAccount(oracle.address(), T0, 3600));
		assertThat(deals.register(longest, null).durationSeconds()).isEqualTo(3600);
	}

	@Test
	void registerRejectsDuplicatesAndUnknownLookups() {
		deals.register(deal, service);
		assertThatThrownBy(() -> deals.register(deal, service)).isInstanceOf(DealAlreadyRegisteredException.class);
		assertThatThrownBy(() -> deals.get(newAddress())).isInstanceOf(NoSuchElementException.class);
	}

	@Test
	void registersAProposalWithoutAWindowAndNeverSettlesItUnaccepted() {
		when(rpc.getAccountInfo(deal)).thenReturn(proposalAccount(T0.plusSeconds(86_400)));

		UptimeDeal tracked = deals.register(deal, null);

		assertThat(tracked.status()).isEqualTo(Status.PROPOSED);
		assertThat(tracked.startsAt()).isNull();
		assertThat(tracked.endsAt()).isNull();
		assertThat(tracked.guaranteeLamports()).isEqualTo(GUARANTEE);
		assertThat(tracked.acceptDeadline()).isEqualTo(T0.plusSeconds(86_400));

		deals.onTrackingEvent(append(new Downtime(service, 404, "HTTP 404", T0.plusSeconds(5))));
		clock.set(T0.plusSeconds(10_000));
		deals.settleDue();
		deals.settleDue();
		assertThat(deals.get(deal).status()).isEqualTo(Status.PROPOSED);
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void takesTheWindowFromTheAcceptanceThenSettlesIt() {
		when(rpc.getAccountInfo(deal)).thenReturn(proposalAccount(T0.plusSeconds(86_400)));
		deals.register(deal, null);
		clock.set(T0.plusSeconds(4));
		deals.settleDue();
		assertThat(deals.get(deal).status()).isEqualTo(Status.PROPOSED);

		// The recipient accepts at T0 + 5; the window runs from there, not from the proposal.
		when(rpc.getAccountInfo(deal)).thenReturn(dealAccount(oracle.address(), T0.plusSeconds(5), 10));
		deals.settleDue();
		UptimeDeal accepted = deals.get(deal);
		assertThat(accepted.status()).isEqualTo(Status.ACTIVE);
		assertThat(accepted.startsAt()).isEqualTo(T0.plusSeconds(5));
		assertThat(accepted.endsAt()).isEqualTo(T0.plusSeconds(15));
		verify(rpc, never()).sendTransaction(any());

		clock.set(T0.plusSeconds(17));
		deals.settleDue();
		ArgumentCaptor<byte[]> tx = ArgumentCaptor.forClass(byte[].class);
		verify(rpc).sendTransaction(tx.capture());
		assertSignedSettlement(tx.getValue(), 10, 10);
	}

	@Test
	void anAcceptedDealClosesEarlyOnAFailureAfterItsAcceptance() {
		when(rpc.getAccountInfo(deal)).thenReturn(proposalAccount(T0.plusSeconds(86_400)));
		deals.register(deal, null);
		when(rpc.getAccountInfo(deal)).thenReturn(dealAccount(oracle.address(), T0.plusSeconds(5), 10));
		clock.set(T0.plusSeconds(5));
		deals.settleDue();

		deals.onTrackingEvent(append(new Downtime(service, 404, "HTTP 404", T0.plusSeconds(8))));

		assertThat(deals.get(deal).upSeconds()).isEqualTo(8);
		assertThat(deals.get(deal).totalSeconds()).isEqualTo(10);
	}

	@Test
	void aWithdrawnOrRejectedProposalIsMarkedCancelled() {
		when(rpc.getAccountInfo(deal)).thenReturn(proposalAccount(T0.plusSeconds(86_400)));
		deals.register(deal, null);
		when(rpc.getAccountInfo(deal)).thenReturn(null);
		when(rpc.getSignaturesForAddress(deal, 10)).thenReturn(List.of("rejectTx"));
		when(rpc.getTransactionLogs("rejectTx")).thenReturn(List.of(dealCancelledLog(deal, payer, AMOUNT)));

		deals.settleDue();

		assertThat(deals.get(deal).status()).isEqualTo(Status.CANCELLED);
		assertThat(deals.get(deal).signature()).isEqualTo("rejectTx");
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void anRpcFailureWhileCheckingAProposalKeepsItWaiting() {
		when(rpc.getAccountInfo(deal)).thenReturn(proposalAccount(T0.plusSeconds(86_400)));
		deals.register(deal, null);
		when(rpc.getAccountInfo(deal)).thenThrow(new SolanaRpcException("down"));

		deals.settleDue();
		deals.settleDue();

		assertThat(deals.get(deal).status()).isEqualTo(Status.PROPOSED);
		assertThat(deals.get(deal).attempts()).isZero();
		assertThat(deals.get(deal).error()).isNull();
	}

	@Test
	void listsTheMostRecentProposalFirst() {
		String older = newAddress();
		when(rpc.getAccountInfo(older)).thenReturn(proposalAccount(T0.plusSeconds(100)));
		when(rpc.getAccountInfo(deal)).thenReturn(proposalAccount(T0.plusSeconds(200)));
		deals.register(older, null);
		deals.register(deal, null);

		assertThat(deals.list()).extracting(UptimeDeal::address).containsExactly(deal, older);
	}

	@Test
	void aHealthyWindowSettlesAtItsEndAndPaysTheRecipient() {
		deals.register(deal, null);
		clock.set(T0.plusSeconds(12).plusMillis(999));
		deals.settleDue();
		verify(rpc, never()).sendTransaction(any());

		clock.set(T0.plusSeconds(13));
		deals.settleDue();

		ArgumentCaptor<byte[]> tx = ArgumentCaptor.forClass(byte[].class);
		verify(rpc).sendTransaction(tx.capture());
		assertSignedSettlement(tx.getValue(), 10, 10);
		UptimeDeal sent = deals.get(deal);
		assertThat(sent.status()).isEqualTo(Status.ACTIVE);
		assertThat(sent.signature()).isEqualTo("sig1");

		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(false, null));
		deals.settleDue();
		assertThat(deals.get(deal).status()).isEqualTo(Status.ACTIVE);

		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, null));
		when(rpc.getTransactionLogs("sig1")).thenReturn(List.of(dealSettledLog(deal, 10, 10, true, AMOUNT)));
		deals.settleDue();
		UptimeDeal settled = deals.get(deal);
		assertThat(settled.status()).isEqualTo(Status.SETTLED);
		assertThat(settled.paidToRecipient()).isTrue();
		verify(rpc, times(1)).sendTransaction(any());

		deals.settleDue();
		verify(rpc, times(2)).getSignatureStatus("sig1");
	}

	@Test
	void aDowntimeThatMakes99PercentUnreachableClosesTheDealAtOnce() {
		deals.register(deal, null);
		TrackingEvent down = append(new Downtime(service, 200, "HTTP 200, status DOWN", T0.plusSeconds(5)));

		deals.onTrackingEvent(down);

		UptimeDeal decided = deals.get(deal);
		assertThat(decided.isSettling()).isTrue();
		assertThat(decided.upSeconds()).isEqualTo(8);
		assertThat(decided.totalSeconds()).isEqualTo(10);

		clock.set(T0.plusSeconds(5));
		deals.settleDue();
		ArgumentCaptor<byte[]> tx = ArgumentCaptor.forClass(byte[].class);
		verify(rpc).sendTransaction(tx.capture());
		assertSignedSettlement(tx.getValue(), 8, 10);
	}

	@Test
	void aFailureThatLeaves99PercentReachableKeepsTheDealOpen() {
		String longDeal = newAddress();
		when(rpc.getAccountInfo(longDeal)).thenReturn(dealAccount(oracle.address(), T0, 1000));
		deals.register(longDeal, null);

		deals.onTrackingEvent(append(new Downtime(service, 404, "HTTP 404", T0.plusSeconds(5))));
		deals.onTrackingEvent(append(new InternalErrorHappened(service, 500, "HTTP 500", T0.plusSeconds(7))));

		assertThat(deals.get(longDeal).isOpen()).isTrue();
		clock.set(T0.plusSeconds(8));
		deals.settleDue();
		verify(rpc, never()).sendTransaction(any());
	}

	@Test
	void trackingFinishedSettlesAtOnceWithTheRestOfTheWindowAsDown() {
		deals.register(deal, null);
		TrackingEvent finished = append(new TrackingFinished(service, T0.plusSeconds(5)));

		deals.onTrackingEvent(finished);

		assertThat(deals.get(deal).upSeconds()).isEqualTo(4);
		assertThat(deals.get(deal).totalSeconds()).isEqualTo(10);
	}

	@Test
	void eventsOfOtherServicesOrBeforeTheWindowAndDecidedDealsAreIgnored() {
		deals.register(deal, null);
		ServiceId other = ServiceId.newId();
		events.append(other, 0, List.of(new TrackingStarted(other, "http://o", Duration.ofSeconds(2), T0)));
		deals.onTrackingEvent(new Downtime(other, 404, "", T0.plusSeconds(5)));
		deals.onTrackingEvent(new Downtime(service, 404, "", T0));
		assertThat(deals.get(deal).isOpen()).isTrue();

		deals.onTrackingEvent(append(new TrackingFinished(service, T0.plusSeconds(5))));
		deals.onTrackingEvent(new Downtime(service, 404, "", T0.plusSeconds(6)));
		assertThat(deals.get(deal).upSeconds()).isEqualTo(4);
	}

	@Test
	void aWindowThatAlreadyEndedSettlesOnTheNextTick() {
		String old = newAddress();
		when(rpc.getAccountInfo(old)).thenReturn(dealAccount(oracle.address(), T0.minusSeconds(50), 10));
		deals.register(old, null);
		deals.settleDue();
		assertThat(deals.get(old).signature()).isEqualTo("sig1");
		assertThat(deals.get(old).upSeconds()).isEqualTo(10);
	}

	@Test
	void missingLogsStillSettleWithAnUnknownVerdict() {
		settleAndSend();
		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, null));
		when(rpc.getTransactionLogs("sig1")).thenReturn(null);
		deals.settleDue();
		assertThat(deals.get(deal).status()).isEqualTo(Status.SETTLED);
		assertThat(deals.get(deal).paidToRecipient()).isNull();
	}

	@Test
	void retriesFailedSendsThenGivesUp() {
		deals.register(deal, null);
		when(rpc.sendTransaction(any())).thenThrow(new SolanaRpcException("node down"));
		clock.set(T0.plusSeconds(13));
		deals.settleDue();
		deals.settleDue();
		assertThat(deals.get(deal).status()).isEqualTo(Status.ACTIVE);
		assertThat(deals.get(deal).attempts()).isEqualTo(2);
		assertThat(deals.get(deal).upSeconds()).isEqualTo(10);
		deals.settleDue();
		assertThat(deals.get(deal).status()).isEqualTo(Status.FAILED);
		assertThat(deals.get(deal).error()).contains("node down");
	}

	@Test
	void failedTransactionsFailTheDeal() {
		settleAndSend();
		when(rpc.getSignatureStatus("sig1")).thenReturn(new SignatureStatus(true, "InstructionError"));
		deals.settleDue();
		assertThat(deals.get(deal).status()).isEqualTo(Status.FAILED);
		assertThat(deals.get(deal).error()).contains("InstructionError");
	}

	@Test
	void resendsWhenASettlementIsNotConfirmedInTime() {
		settleAndSend();
		when(rpc.getSignatureStatus("sig1")).thenReturn(null);
		clock.set(T0.plusSeconds(13 + 31));
		deals.settleDue();
		assertThat(deals.get(deal).signature()).isNull();
		assertThat(deals.get(deal).attempts()).isEqualTo(1);
		when(rpc.sendTransaction(any())).thenReturn("sig2");
		deals.settleDue();
		assertThat(deals.get(deal).signature()).isEqualTo("sig2");
	}

	@Test
	void aDealThatVanishedIsExplainedFromItsHistory() {
		deals.register(deal, null);
		when(rpc.getAccountInfo(deal)).thenReturn(null);
		when(rpc.getSignaturesForAddress(deal, 10)).thenReturn(List.of("other", "settle"));
		when(rpc.getTransactionLogs("other")).thenReturn(List.of(dealSettledLog(newAddress(), 1, 10, false, AMOUNT)));
		when(rpc.getTransactionLogs("settle")).thenReturn(List.of(dealSettledLog(deal, 10, 10, true, AMOUNT)));
		clock.set(T0.plusSeconds(13));
		deals.settleDue();
		UptimeDeal settled = deals.get(deal);
		assertThat(settled.status()).isEqualTo(Status.SETTLED);
		assertThat(settled.signature()).isEqualTo("settle");
		assertThat(settled.paidToRecipient()).isTrue();
	}

	@Test
	void aDealCancelledByItsPayerIsMarkedCancelled() {
		deals.register(deal, null);
		when(rpc.getAccountInfo(deal)).thenReturn(null);
		when(rpc.getSignaturesForAddress(deal, 10)).thenReturn(List.of("cancel"));
		when(rpc.getTransactionLogs("cancel")).thenReturn(List.of(dealCancelledLog(deal, payer, AMOUNT)));
		clock.set(T0.plusSeconds(13));
		deals.settleDue();
		assertThat(deals.get(deal).status()).isEqualTo(Status.CANCELLED);
	}

	@Test
	void aVanishedDealWithoutAClosingEventFails() {
		deals.register(deal, null);
		when(rpc.getAccountInfo(deal)).thenReturn(null);
		when(rpc.getSignaturesForAddress(deal, 10)).thenReturn(List.of());
		clock.set(T0.plusSeconds(13));
		deals.settleDue();
		assertThat(deals.get(deal).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void oneFailingDealDoesNotBlockOthers() {
		String second = newAddress();
		when(rpc.getAccountInfo(second)).thenReturn(dealAccount(oracle.address()));
		deals.register(deal, null);
		deals.register(second, null);
		when(rpc.getAccountInfo(deal)).thenThrow(new SolanaRpcException("boom"));
		clock.set(T0.plusSeconds(13));
		deals.settleDue();
		assertThat(deals.get(deal).attempts()).isEqualTo(1);
		assertThat(deals.get(second).signature()).isEqualTo("sig1");
	}

	@Test
	void fundsTheOracleWhenItIsLowAndToleratesAFaucetFailure() {
		DealService funded = deals(100, service);
		when(rpc.getBalance(oracle.address())).thenReturn(10L);
		funded.register(deal, null);
		verify(rpc).requestAirdrop(oracle.address(), 1_000);

		String second = newAddress();
		when(rpc.getAccountInfo(second)).thenReturn(dealAccount(oracle.address()));
		when(rpc.getBalance(oracle.address())).thenThrow(new SolanaRpcException("no faucet"));
		assertThat(funded.register(second, null).status()).isEqualTo(Status.ACTIVE);
	}

	@Test
	void noFundingWhenDisabled() {
		deals.register(deal, null);
		verify(rpc, never()).getBalance(anyString());
		verify(rpc, never()).requestAirdrop(anyString(), anyLong());
	}

	private void settleAndSend() {
		deals.register(deal, null);
		clock.set(T0.plusSeconds(13));
		deals.settleDue();
		assertThat(deals.get(deal).signature()).isEqualTo("sig1");
	}

	private TrackingEvent append(TrackingEvent event) {
		events.append(event.serviceId(), events.load(event.serviceId()).size(), List.of(event));
		return event;
	}

	private AccountInfo dealAccount(String dealOracle) {
		return dealAccount(dealOracle, T0.plusSeconds(1), 10);
	}

	private AccountInfo dealAccount(String dealOracle, Instant startsAt, long durationSeconds) {
		return new AccountInfo(PROGRAM_ID, AMOUNT + GUARANTEE + 2_000_000,
				dealData(payer, recipient, dealOracle, 1, AMOUNT, startsAt.getEpochSecond(), durationSeconds));
	}

	private AccountInfo proposalAccount(Instant acceptDeadline) {
		return new AccountInfo(PROGRAM_ID, AMOUNT + 2_000_000, proposalData(payer, recipient, oracle.address(), 1,
				AMOUNT, GUARANTEE, 10, acceptDeadline.getEpochSecond()));
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
