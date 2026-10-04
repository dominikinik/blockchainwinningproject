package com.example.monitor.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.deal.DealAlreadyRegisteredException;
import com.example.monitor.domain.deal.UptimeDeal;
import com.example.monitor.domain.deal.UptimeDealRepository;
import com.example.monitor.domain.deal.Verdict;

/** Behaviour every {@link UptimeDealRepository} must have. */
abstract class UptimeDealRepositoryContract {

	static final Instant T0 = Instant.parse("2026-10-04T12:00:00.123456Z");

	UptimeDealRepository repo;

	ServiceId a = ServiceId.newId();

	ServiceId b = ServiceId.newId();

	abstract UptimeDealRepository newRepository();

	@BeforeEach
	void create() {
		repo = newRepository();
	}

	static UptimeDeal deal(String address, ServiceId service, Instant startsAt) {
		return UptimeDeal.register(address, service, "Payer", "Recipient", 500_000_000L, 10, startsAt, T0);
	}

	@Test
	void addFindAndRoundTripEveryField() {
		UptimeDeal deal = deal("D1", a, T0);
		repo.add(deal);
		assertThat(repo.find("D1")).contains(deal);

		UptimeDeal full = deal.decide(new Verdict(8, 10)).sent("sig", T0.plusSeconds(3)).failedAttempt("lost", 5)
			.sent("sig2", T0.plusSeconds(4)).settled(false);
		repo.update(full);
		assertThat(repo.find("D1")).contains(full);
		assertThat(repo.find("nope")).isEmpty();
	}

	@Test
	void duplicatesAndUnknownUpdatesAreRejected() {
		repo.add(deal("D1", a, T0));
		assertThatThrownBy(() -> repo.add(deal("D1", b, T0))).isInstanceOf(DealAlreadyRegisteredException.class);
		assertThatThrownBy(() -> repo.update(deal("D2", a, T0))).isInstanceOf(NoSuchElementException.class);
		assertThat(repo.find("D1").orElseThrow().serviceId()).isEqualTo(a);
	}

	@Test
	void listsNewestWindowFirstAndFiltersActiveByService() {
		UptimeDeal old = deal("D1", a, T0);
		UptimeDeal newer = deal("D2", b, T0.plusSeconds(60));
		UptimeDeal closed = deal("D3", a, T0.plusSeconds(30));
		repo.add(old);
		repo.add(newer);
		repo.add(closed);
		repo.update(closed.cancelled("c"));

		assertThat(repo.findAll()).extracting(UptimeDeal::address).containsExactly("D2", "D3", "D1");
		assertThat(repo.findActive()).extracting(UptimeDeal::address).containsExactly("D1", "D2");
		assertThat(repo.findActive(a)).extracting(UptimeDeal::address).containsExactly("D1");
		assertThat(repo.findActive(ServiceId.newId())).isEmpty();
	}

}
