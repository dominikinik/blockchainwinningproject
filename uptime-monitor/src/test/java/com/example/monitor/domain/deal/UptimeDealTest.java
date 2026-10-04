package com.example.monitor.domain.deal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.deal.UptimeDeal.Status;

class UptimeDealTest {

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

	UptimeDeal deal = UptimeDeal.register("Deal", ServiceId.newId(), "Payer", "Recipient", 500, 700, 10,
			T0.plusSeconds(86_400), T0, T0);

	UptimeDeal proposal = UptimeDeal.register("Prop", ServiceId.newId(), "Payer", "Recipient", 500, 700, 10,
			T0.plusSeconds(86_400), null, T0);

	@Test
	void aProposalHasNoWindowUntilAccepted() {
		assertThat(proposal.status()).isEqualTo(Status.PROPOSED);
		assertThat(proposal.startsAt()).isNull();
		assertThat(proposal.endsAt()).isNull();
		assertThat(proposal.isOpen()).isFalse();

		UptimeDeal accepted = proposal.accepted(T0.plusSeconds(5));
		assertThat(accepted.status()).isEqualTo(Status.ACTIVE);
		assertThat(accepted.startsAt()).isEqualTo(T0.plusSeconds(5));
		assertThat(accepted.endsAt()).isEqualTo(T0.plusSeconds(15));
		assertThat(accepted.isOpen()).isTrue();
		assertThat(accepted.guaranteeLamports()).isEqualTo(700);
		assertThatIllegalStateException().isThrownBy(() -> accepted.accepted(T0));
	}

	@Test
	void anActiveDealNeedsAWindowStart() {
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
			.isThrownBy(() -> new UptimeDeal("D", ServiceId.newId(), "P", "R", 1, 1, 10, T0, null, Status.ACTIVE, null,
					null, null, null, null, 0, null, T0));
	}

	@Test
	void aNewDealIsOpenWithTheWindowFromTheChain() {
		assertThat(deal.status()).isEqualTo(Status.ACTIVE);
		assertThat(deal.isOpen()).isTrue();
		assertThat(deal.isSettling()).isFalse();
		assertThat(deal.endsAt()).isEqualTo(T0.plusSeconds(10));
		assertThat(deal.attempts()).isZero();
	}

	@Test
	void sendAndSettle() {
		UptimeDeal sent = deal.sent("sig", T0.plusSeconds(12));
		assertThat(sent.signature()).isEqualTo("sig");
		assertThat(sent.sentAt()).isEqualTo(T0.plusSeconds(12));
		UptimeDeal settled = sent.settled(true);
		assertThat(settled.status()).isEqualTo(Status.SETTLED);
		assertThat(settled.paidToRecipient()).isTrue();
		assertThat(settled.isOpen()).isFalse();
	}

	@Test
	void failedAttemptsClearTheSignatureAndFailAfterTheMaximum() {
		UptimeDeal once = deal.sent("sig", T0).failedAttempt("lost", 2);
		assertThat(once.status()).isEqualTo(Status.ACTIVE);
		assertThat(once.signature()).isNull();
		assertThat(once.isOpen()).isTrue();
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
		assertThat(settledBy.upChecks()).isEqualTo(3);
		assertThat(deal.failed("gone").error()).isEqualTo("gone");
	}

}
