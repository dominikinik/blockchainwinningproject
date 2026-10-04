package com.example.monitor.support;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.deal.DealAlreadyRegisteredException;
import com.example.monitor.domain.deal.UptimeDeal;
import com.example.monitor.domain.deal.UptimeDealRepository;

/** Test fake of {@link UptimeDealRepository}; {@code UptimeDealRepositoryContract} keeps it honest. */
public class InMemoryUptimeDealRepository implements UptimeDealRepository {

	private static final Comparator<UptimeDeal> BY_DEADLINE = Comparator.comparing(UptimeDeal::acceptDeadline)
		.thenComparing(UptimeDeal::address);

	private final Map<String, UptimeDeal> deals = new LinkedHashMap<>();

	@Override
	public synchronized void add(UptimeDeal deal) {
		if (deals.putIfAbsent(deal.address(), deal) != null) {
			throw new DealAlreadyRegisteredException(deal.address());
		}
	}

	@Override
	public synchronized void update(UptimeDeal deal) {
		if (deals.replace(deal.address(), deal) == null) {
			throw new NoSuchElementException("Deal " + deal.address() + " is not registered");
		}
	}

	@Override
	public synchronized Optional<UptimeDeal> find(String address) {
		return Optional.ofNullable(deals.get(address));
	}

	@Override
	public synchronized List<UptimeDeal> findAll() {
		return deals.values().stream()
			.sorted(Comparator.comparing(UptimeDeal::acceptDeadline).reversed().thenComparing(UptimeDeal::address))
			.toList();
	}

	@Override
	public synchronized List<UptimeDeal> findUnfinished() {
		return deals.values().stream()
			.filter(d -> d.status() == UptimeDeal.Status.PROPOSED || d.status() == UptimeDeal.Status.ACTIVE)
			.sorted(BY_DEADLINE)
			.toList();
	}

	@Override
	public synchronized List<UptimeDeal> findActive(ServiceId serviceId) {
		return findUnfinished().stream()
			.filter(d -> d.status() == UptimeDeal.Status.ACTIVE && d.serviceId().equals(serviceId))
			.toList();
	}

}
