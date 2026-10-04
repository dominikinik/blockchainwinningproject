package com.example.monitor.domain.deal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.deal.UptimeDeal.Status;

class UptimeDealTest {

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

	UptimeDeal deal = UptimeDeal.register("Deal", ServiceId.newId(), "Payer", "Recipient", 500, 10, T0, T0);

	@Test
	void aNewDealIsOpenWithTheWindowFromTheChain() {
		assertThat(deal.status()).isEqualTo(Status.ACTIVE);
		assertThat(deal.isOpen()).isTrue();
		assertThat(deal.isSettling()).isFalse();
		assertThat(deal.endsAt()).isEqualTo(T0.plusSeconds(10));
		assertThat(deal.attempts()).isZero();
	}

	@Test
	void decidingFixesTheVerdictOnce() {
		UptimeDeal decided = deal.decide(new Verdict(8, 10));
		assertThat(decided.isSettling()).isTrue();
		assertThat(decided.upSeconds()).isEqualTo(8);
		assertThat(decided.totalSeconds()).isEqualTo(10);
		assertThatIllegalStateException().isThrownBy(() -> decided.decide(new Verdict(10, 10)));
		assertThatIllegalStateException().isThrownBy(() -> deal.failed("x").decide(new Verdict(10, 10)));
	}

	@Test
	void sendAndSettle() {
		UptimeDeal sent = deal.decide(new Verdict(10, 10)).sent("sig", T0.plusSeconds(12));
		assertThat(sent.signature()).isEqualTo("sig");
		assertThat(sent.sentAt()).isEqualTo(T0.plusSeconds(12));
		UptimeDeal settled = sent.settled(true);
		assertThat(settled.status()).isEqualTo(Status.SETTLED);
		assertThat(settled.paidToRecipient()).isTrue();
		assertThat(settled.isOpen()).isFalse();
	}

	@Test
	void failedAttemptsKeepTheVerdictAndFailAfterTheMaximum() {
		UptimeDeal once = deal.decide(new Verdict(8, 10)).sent("sig", T0).failedAttempt("lost", 2);
		assertThat(once.status()).isEqualTo(Status.ACTIVE);
		assertThat(once.signature()).isNull();
		assertThat(once.upSeconds()).isEqualTo(8);
		assertThat(once.error()).isEqualTo("lost");
		UptimeDeal twice = once.failedAttempt("lost again", 2);
		assertThat(twice.status()).isEqualTo(Status.FAILED);
		assertThat(twice.attempts()).isEqualTo(2);
	}

	@Test
	void closedOnChainTransitions() {
		assertThat(deal.cancelled("c").status()).isEqualTo(Status.CANCELLED);
		UptimeDeal settledBy = deal.settledBy("s", false, 3L, 10L);
		assertThat(settledBy.status()).isEqualTo(Status.SETTLED);
		assertThat(settledBy.upSeconds()).isEqualTo(3);
		assertThat(deal.failed("gone").error()).isEqualTo("gone");
	}

}
