package com.example.monitor.domain.deal;

import java.util.List;
import java.util.Optional;

import com.example.monitor.domain.ServiceId;

/** Port: storage of deals. */
public interface UptimeDealRepository {

	/** Inserts a new deal. @throws DealAlreadyRegisteredException if one with that address exists */
	void add(UptimeDeal deal);

	/** Replaces a stored deal. @throws java.util.NoSuchElementException if it doesn't exist */
	void update(UptimeDeal deal);

	Optional<UptimeDeal> find(String address);

	/** Every deal, most recently proposed (latest accept deadline) first. */
	List<UptimeDeal> findAll();

	/** Every deal that is still {@code PROPOSED} or {@code ACTIVE}. */
	List<UptimeDeal> findUnfinished();

	/** Every {@code ACTIVE} deal of one service. */
	List<UptimeDeal> findActive(ServiceId serviceId);

}
